/*
 * (c) Copyright 2016 Hewlett Packard Enterprise Development LP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cogx.compiler.codegenerator.opencl.hyperkernels

import cogx.compiler.codegenerator.opencl.fragments._
import cogx.platform.types._
import cogx.cogmath.geometry.Shape
import cogx.compiler.parser.op.MatrixTransformMatrixOp

/** Computes the matrix multiply of 2 fields considered to be matrices.
  *
  * One subtlety handled by this implementation is how to keep the GPU efficient
  * if either the rows or columns of the resultant field <= 16.  If the output
  * field has say only 4 rows, then a workgroup of 4x64 is preferred over 16x16.
  * However, in that case we don't read in both input tiles before each execution
  * of the dot-product-summing loop.  Another approach to evaluate is whether
  * a matrix-vector multiply kernel could handle this case if it were "thread
  * coarsened" so that each thread computed the 4 rows concurrently.
  *
  * Another subtlety of the kernel is introduced by the need to optionally
  * transpose either input.  The transposition is handled when the input is
  * read into each tile, so that the tile data appears as it would had the input
  * been transposed by a separate kernel in advance of this one.  The workgroup shape
  * and tile shape are based on the output matrix shape and are unaffected by any
  * transposing that happens to the inputs.  One trick is that the addresses used to
  * read in the transposed tile is not (_localColumn, _localRow), as this would
  * result in poor coalescing of global memory addresses within a warp.  Consecutive
  * global memory addresses are used as much as possible during tile read-in, with
  * bank conflicts handled during the shared memory tile write handled by padding the
  * tile columns by 1.
  *
  * This kernel is created as a special case by the factory method of the
  * MatrixMatrixTransformHyperKernel.
  *
  * This version has each thread processing a "mini-tile" of the output, an approach
  * also termed thread coarsening.  This expands the registers used by the kernel, so
  * may effect GPU occupancy.  The big win comes in feeding the fused multiply-adds with
  * operands.  Each operand is read from shared memory into a register, but then used in
  * multiple FMA's.  The non-coarsened version of this kernel uses each shared-memory-read
  * operand once (within a thread).
  *
  * @param in The input virtual field register driving this kernel.
  * @param op The binary opcode for this operation.
  * @param resultType The FieldType of the result of this kernel.
  *
  * @author Dick Carter
  */
private[cogx]
class MatrixMatrixTransform0DFieldTiledHyperKernel(in: Array[VirtualFieldRegister],
                                    op: MatrixTransformMatrixOp,
                                    resultType: FieldType)
        extends HyperKernel(op, in, resultType, SmallTensorAddressing)
{
  val matrix1Type = in(0).fieldType
  val matrix1Shape = matrix1Type.tensorShape
  val matrix1Columns = matrix1Shape(1)
  val matrix1Rows = matrix1Shape(0)

  val matrix2Type = in(1).fieldType
  val matrix2Shape = matrix2Type.tensorShape
  val matrix2Columns = matrix2Shape(1)
  val matrix2Rows = matrix2Shape(0)

  val resultShape = resultType.tensorShape
  val resultColumns = resultShape(1)
  val resultRows = resultShape(0)

  val dotProductSize =
    if (op.transposeIn1) matrix1Type.tensorRows else matrix1Type.tensorColumns

  // 0D fields are run by default by 1D kernels with 256-long 1D local work groups.
  // We want this kernel to run more like a 2D kernel, so we set the fictional
  // workfieldtype to be a ScalarField whose fieldshape is the kernel's result tensorshape.

  import MatrixMatrixTransform0DFieldTiledHyperKernel.miniTileRows
  import MatrixMatrixTransform0DFieldTiledHyperKernel.miniTileColumns

  var workGroupRows = 16
  var workGroupColumns = 16
  
  val globalWorkRows = Math.ceil(resultRows.toFloat / miniTileRows).toInt
  val globalWorkColumns = Math.ceil(resultColumns.toFloat / miniTileColumns).toInt
  val globalWorkShape = Shape(globalWorkRows, globalWorkColumns)

  while (workGroupRows/2 >= globalWorkRows) {
    workGroupRows /= 2
    if (workGroupColumns < globalWorkColumns)
      workGroupColumns *= 2
  }
  while (workGroupColumns/2 >= globalWorkColumns) {
    workGroupColumns /= 2
    if (workGroupRows < globalWorkRows)
      workGroupRows *= 2
  }

  val tiledWorkGroupRows = workGroupRows * miniTileRows
  val tiledWorkGroupColumns = workGroupColumns * miniTileColumns

  override lazy val workFieldType = new FieldType(globalWorkShape, Shape(), resultType.elementType)
  override lazy val workGroup =
    HyperKernel.computeWorkGroupParameters(workFieldType, addressing,
      workGroupRows, workGroupColumns)

  // If the local workgroup shape is not square, the tile read-in and dot-product summing
  // will consist of an inner loop and an output loop.

  val tile1Reads = Math.ceil(dotProductSize.toDouble/tiledWorkGroupColumns).toInt
  val tile2Reads = Math.ceil(dotProductSize.toDouble/tiledWorkGroupRows).toInt

  val minTileReads = Math.min(tile1Reads, tile2Reads)

  val innerLoopReads = Math.max(tiledWorkGroupColumns/tiledWorkGroupRows, tiledWorkGroupRows/tiledWorkGroupColumns)

  val innerLoopFMAs = Math.min(tiledWorkGroupRows, tiledWorkGroupColumns)

  // Current level of code indenting
  var indent = 4
  // Helper function to output code in a human-readable form with proper indenting
  def codeAppend(s: String): Unit = {
    code append " " * indent
    code append s
  }

  val code = new StringBuffer

  codeAppend(s"// Local memory holds a WorkGroupRows x WorkGroupColumns tile,\n")
  indent += 4
  // If local memory is a 2D structure, we need to add 1 to avoid bank conflicts
  val bankConflictKiller =
    if (tiledWorkGroupColumns > 1 && tiledWorkGroupRows > 1) {
      codeAppend(s"// but we add 1 to columns to prevent bank conflicts.\n")
      "+1"
    }
    else
      ""

  // Tiles of the inputs, stored to speed the dot-product.
  // If the input fields need to be transposed, the transposition is performed when the tiles
  // are first written, not as they are then read.
  codeAppend(s"__local float tile1[$tiledWorkGroupRows][$tiledWorkGroupColumns$bankConflictKiller];\n")
  codeAppend(s"__local float tile2[$tiledWorkGroupRows][$tiledWorkGroupColumns$bankConflictKiller];\n")

  // All input and output fields are 0D MatrixFields.  We perform all reads and writes with
  // readElementNonLocal with the 'tensorElement' variable set appropriately.
  codeAppend(s"row = 0; column = 0;\n")
  codeAppend(s"int inRow = 0;\n")
  codeAppend(s"int inColumn = 0;\n")

  // In order to read in a transposed tile, we could swap _localRow and _localColumn, but that
  // would create an inefficient load pattern from global memory.  This code renumbers the threads
  // of a 4 x 64 workgroup (say) like this:

  // (_localRow, _localColumn)

  // From:

  // (0,0)  (0,1)  (0,2)  (0,3)  (0,4) ... (0,63)
  // (1,0)  (1,1)  (1,2)  (1,3)  (1,4) ... (1,63)
  // (2,0)  (2,1)  (2,2)  (2,3)  (2,4) ... (2,63)
  // (3,0)  (3,1)  (3,2)  (3,3)  (3,4) ... (3,63)

  // To:

  // (0,0)  (0,1)  (0,2)  (0,3)
  // (1,0)  (1,1)  (1,2)  (1,3)
  //
  // ...
  //
  // (63,0)  (63,1)  (63,2)  (63,3)


  if (op.transposeIn1 || op.transposeIn2) {
    if (workGroupRows != workGroupColumns) {
      codeAppend(s"int localIndex = _localRow * _localColumns + _localColumn;\n")
      codeAppend(s"int transposedLocalRow = localIndex / _localRows;\n")
      codeAppend(s"int transposedLocalColumn = localIndex % _localRows;\n")
    }
    else {
      codeAppend(s"int transposedLocalRow = _localRow;\n")
      codeAppend(s"int transposedLocalColumn = _localColumn;\n")
    }
  }

  // retVal is the final result to be written by this thread
  // innerLoopSum holds the dot-product delta accumulated by one tile
  for (rTileIndex <- 0 until miniTileRows; cTileIndex <- 0 until miniTileColumns) {
    codeAppend(s"float retVal_${rTileIndex}_$cTileIndex = 0.0f;\n")
    codeAppend(s"float innerLoopSum_${rTileIndex}_$cTileIndex = 0.0f;\n")
  }
  codeAppend(s"for (int i = 0; i < $minTileReads; i++) {\n")

  // Read a tile of the given input (1 or 2) based on a string-based description of the tile upper-left corner
  // The routine looks up important parameters- the size of the input matrix and whether that input is transposed.
  def readInputTile(inputNum: Int, tileOriginRow: String, tileOriginColumn: String): Unit = {
    require(inputNum == 1 || inputNum == 2, "Improper tile number.")
    val transpose = if (inputNum == 1) op.transposeIn1 else op.transposeIn2
    val inputIndex = inputNum-1
    val inType = in(inputIndex).fieldType
    val matrixColumns = inType.tensorColumns
    val matrixRows = inType.tensorRows
    codeAppend(s"    // read in tile from input $inputNum\n")

    if (transpose) {
      codeAppend(s"    inRow = $tileOriginColumn + transposedLocalRow;\n")
      codeAppend(s"    for (int cTileIndex = 0; cTileIndex < $miniTileColumns; cTileIndex++, inRow += $workGroupColumns) {\n")
      codeAppend(s"        inColumn = $tileOriginRow + transposedLocalColumn;\n")
      codeAppend(s"        for (int rTileIndex = 0; rTileIndex < $miniTileRows; rTileIndex++, inColumn += $workGroupRows) {\n")
      codeAppend(s"            tensorElement = inRow * $matrixColumns + inColumn;\n")
      codeAppend(s"            tile$inputNum[transposedLocalColumn+rTileIndex*$workGroupRows][transposedLocalRow+cTileIndex*$workGroupColumns] = \n")
      codeAppend(s"                (inRow < $matrixRows && inColumn < $matrixColumns) ? readElementNonlocal(@in$inputIndex) : 0.0f;\n")
      codeAppend(s"        }\n")
      codeAppend(s"    }\n")
    }
    else {
      codeAppend(s"    inRow = $tileOriginRow + _localRow;\n")
      codeAppend(s"    for (int rTileIndex = 0; rTileIndex < $miniTileRows; rTileIndex++, inRow += $workGroupRows) {\n")
      codeAppend(s"        inColumn = $tileOriginColumn + _localColumn;\n")
      codeAppend(s"        for (int cTileIndex = 0; cTileIndex < $miniTileColumns; cTileIndex++, inColumn += $workGroupColumns) {\n")
      codeAppend(s"            tensorElement = inRow * $matrixColumns + inColumn;\n")
      codeAppend(s"            tile$inputNum[_localRow+rTileIndex*$workGroupRows][_localColumn+cTileIndex*$workGroupColumns] = \n")
      codeAppend(s"                (inRow < $matrixRows && inColumn < $matrixColumns) ? readElementNonlocal(@in$inputIndex) : 0.0f;\n\n")
      codeAppend(s"        }\n")
      codeAppend(s"    }\n")
    }
  }

  // Read in one tile from the tile read in once per outer loop
  if (minTileReads == tile1Reads)
    readInputTile(1, s"$miniTileRows * (_row - _localRow)", s"i * $tiledWorkGroupColumns")
  else
    readInputTile(2, s"i * $tiledWorkGroupRows", s"$miniTileColumns * (_column - _localColumn)")

  // Inner loop, if needed based on a non-square tile shape.
  codeAppend(s"    int j = 0;\n")
  if (innerLoopReads > 1) {
    codeAppend(s"    for (; j < $innerLoopReads; j++) {\n")
    indent += 4
  }

  // Read in one tile from the tile read in once per inner loop
  codeAppend(s"\n")
  if (minTileReads == tile1Reads)
    readInputTile(2, s"(i * $innerLoopReads + j) * $tiledWorkGroupRows", s"$miniTileColumns * (_column - _localColumn)")
  else
    readInputTile(1, s"$miniTileRows * (_row - _localRow)", s"(i * $innerLoopReads + j) * $tiledWorkGroupColumns")

  // Make sure tile writes above have completed before we read the tiles
  codeAppend(s"    barrier(CLK_LOCAL_MEM_FENCE);\n\n")
  codeAppend(s"    // sum up as much of the dot product as is possible given the data read in\n")

  def codeAppendInnerLoopSum(rTileIndex: Int, cTileIndex: Int): Unit = {
    for (fma <- 0 until innerLoopFMAs) {
      if (innerLoopReads == 1)
        codeAppend(s"    innerLoopSum += tile1[_localRow][$fma] * tile2[$fma][_localColumn];\n")
      else if (minTileReads == tile1Reads)
        codeAppend(s"    innerLoopSum += tile1[_localRow][$innerLoopFMAs*j + $fma] * tile2[$fma][_localColumn];\n")
      else
        codeAppend(s"    innerLoopSum += tile1[_localRow][$fma] * tile2[$innerLoopFMAs*j + $fma][_localColumn];\n")
    }
  }

  for (rTileIndex <- 0 until miniTileRows)
    codeAppend(s"    float tile1val_$rTileIndex;\n")
  for (cTileIndex <- 0 until miniTileColumns)
    codeAppend(s"    float tile2val_$cTileIndex;\n")

  for (fma <- 0 until innerLoopFMAs) {
    for (rTileIndex <- 0 until miniTileRows) {
      if (innerLoopReads == 1 || minTileReads != tile1Reads)
        codeAppend(s"    tile1val_$rTileIndex = tile1[$miniTileRows*_localRow + $rTileIndex][$fma];\n")
      else
        codeAppend(s"    tile1val_$rTileIndex = tile1[$miniTileRows*_localRow + $rTileIndex][$innerLoopFMAs*j + $fma];\n")
    }
    for (cTileIndex <- 0 until miniTileColumns) {
      if (innerLoopReads == 1 || minTileReads == tile1Reads)
        codeAppend(s"    tile2val_$cTileIndex = tile2[$fma][$miniTileColumns*_localColumn + $cTileIndex];\n")
      else
        codeAppend(s"    tile2val_$cTileIndex = tile2[$innerLoopFMAs*j + $fma][$miniTileColumns*_localColumn + $cTileIndex];\n")
    }
    for (rTileIndex <- 0 until miniTileRows; cTileIndex <- 0 until miniTileColumns)
      codeAppend(s"    innerLoopSum_${rTileIndex}_$cTileIndex += tile1val_$rTileIndex * tile2val_$cTileIndex;\n")
  }

  codeAppend(s"\n")
  // Make sure the tile reads above have completed before we overwrite the tiles
  codeAppend(s"    barrier(CLK_LOCAL_MEM_FENCE);\n")
  if (innerLoopReads > 1) {
    indent -= 4
    codeAppend(s"    }\n")
  }
  for (rTileIndex <- 0 until miniTileRows; cTileIndex <- 0 until miniTileColumns) {
    codeAppend(s"    retVal_${rTileIndex}_$cTileIndex += innerLoopSum_${rTileIndex}_$cTileIndex;\n")
    codeAppend(s"    innerLoopSum_${rTileIndex}_$cTileIndex = 0.0f;\n")
  }

  // Have yet to play with this on the tiled version- may result in excessive register pressure,
  // since the compiler may try to allocate these temp registers uniquely for each element of the mini-tile
  // Knuth et.al. TwoSum algorithm: overhead 6 flops, but outside inner loop and largely hidden by memory latency
  // Note that innerLoopSum will be left with a residual amount that could not be effectively summed into retVal.
//  codeAppend(s"    float newRetVal = retVal + innerLoopSum;\n")
//  codeAppend(s"    float retValChange = newRetVal - retVal;\n")
//  codeAppend(s"    float newInnerLoopSum = (retVal - (newRetVal - retValChange)) + (innerLoopSum - retValChange);\n")
//  codeAppend(s"    retVal = newRetVal;\n")
//  codeAppend(s"    innerLoopSum = newInnerLoopSum;\n")

  codeAppend(s"}\n")
  // Remember that the threads are assigned based on a virtual workField that has rows and columns equal
  // to the tensorRows and tensorColumns of the output 0D MatrixField.  We make the adjustments to
  // tensorElement to make a "pseudo-local" write of the output.
  codeAppend(s"if (_row * $miniTileRows < $resultRows - $miniTileRows && _column * $miniTileColumns < $resultColumns - $miniTileColumns) {\n")
  for (rTileIndex <- 0 until miniTileRows; cTileIndex <- 0 until miniTileColumns) {
    codeAppend(s"    tensorElement = ($miniTileRows * _row + $rTileIndex) * $resultColumns + $miniTileColumns * _column + $cTileIndex;\n")
    codeAppend(s"    @outElementNonlocal0 = retVal_${rTileIndex}_$cTileIndex;\n")
  }
  codeAppend(s"}\n")
  codeAppend(s"else {\n")
  for (rTileIndex <- 0 until miniTileRows; cTileIndex <- 0 until miniTileColumns) {
    codeAppend(s"    if ($miniTileRows * _row + $rTileIndex < $resultRows && $miniTileColumns * _column + $cTileIndex < $resultColumns) {\n")
    codeAppend(s"        tensorElement = ($miniTileRows * _row + $rTileIndex) * $resultColumns + $miniTileColumns * _column + $cTileIndex;\n")
    codeAppend(s"        @outElementNonlocal0 = retVal_${rTileIndex}_$cTileIndex;\n")
    codeAppend(s"    }\n")
  }
  codeAppend(s"}\n")

  addCode(code.toString)
  //debugCompile
}

object MatrixMatrixTransform0DFieldTiledHyperKernel {
  // These must be identical or a factor of 2 apart I think, since handling non-square
  // tile size is tricky

  // Setting was best for one big example on Titan Black, your GPU mileage may vary.
  val miniTileRows = 4
  val miniTileColumns = 2

  def tileSize = miniTileColumns * miniTileRows
}
