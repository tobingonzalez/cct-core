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

package cogx.platform.opencl

import cogx.platform.cpumemory.FieldMemory
import com.jogamp.opencl._
import util.CLPlatformFilters._
import util.{Filter, JOCLVersion}
import com.jogamp.opencl.CLDevice.Type._
import cogx.parameters.Cog
import cogx.platform.opencl.OpenCLEventCache._

/** The OpenCLPlatform provides access to GPUs on a node, taking care of all
  * of the housekeeping necessary to make them functional.
  *
  * The primary interfaces here are: `devices`, which returns the OpenCL devices
  * available for this platform; and `release`, which must be called before
  * the program exits to release all OpenCL resources.
  *
  * @author Greg Snider
  */
private[cogx]
class OpenCLPlatform {
  /** Enable profiling of OpenCL kernel execution by setting Cog.profile or
    * by using the JVM arg -Dcog.profile. There are rumors online
    * that enabling this disables multiple, concurrent kernel launch on some
    * platforms. So probably best to leave false for best performance.
    *
    * Side note: enabling this on Nvidia seems to increase the probability
    * of the application crashing with an Access Violation. It's not known if
    * this is due to a race within Cog itself (possible because of the use of
    * actors), or is a problem with Nvidia's driver/hardware.
    *
    * Profiling enabled by a JVM arg in a number of equivalent forms:
    *
    * -Dcog.profile
    * -Dcog.profile=1
    * -Dcog.profile=true
    *
    * Set cycle-count interval between outputs as in:
    *
    * -Dcog.profileSize=100
    *
    */

  /** The actual platform used. */
  private val platform: CLPlatform = selectPlatform()
  /** Devices on platform. */
  private var clDevices: Array[OpenCLDevice] = null
  /** Context on platform. */
  private var clContext_ : CLContext = null
  /** The context created on this platform. */
  def clContext = clContext_;

  /** The devices available on the platform. */
  def devices: Array[OpenCLDevice] = {
    require(platform != null)
    if (clDevices == null)
      clDevices = createDevices()
    clDevices
  }
  
  /** use a cpu-side memory pool-allocator specific to this context. */
  val fieldMemoryAllocator = FieldMemory()

  /** Release all resources EXCEPT context, devices and their command queues. */
  def release() {
    synchronized {
      if (clDevices != null) {
        devices.foreach(_.release())
      }
    }
    if (Cog.verboseOpenCLPlatform)
      println("  releasing context " + clContext_.toString)
    clContext_.release()
    if (Cog.verboseOpenCLPlatform)
      println("OpenCLPlatform: SHUT DOWN.")
    fieldMemoryAllocator.destroyAll()
  }

  /** Print out information about the platform and its devices for debugging. */
  def print() {
    println(platformDescriptor)
  }

  /** Get a string description of the platform. */
  def platformDescriptor: String = {
    var string = "OpenCL Platform: " + this.toString + "\n"
    for (device <- devices)
      string += "  Device: " + device.toString + " (" + device.clDevice.getAddressBits + "-bit addr)\n"
    string
  }

  /** Return true if this is an NVidea OpenCL platform. */
  private[opencl] def isNVidia =
    platform.getVendor.toLowerCase.contains("nvidia")

  /** Return true if this is an Intel OpenCL platform. */
  private[opencl] def isIntel =
    platform.getVendor.toLowerCase.contains("intel")

  /** Return true if this is an AMD OpenCL platform. */
  private[opencl] lazy val isAMD =
    platform.getVendor.toLowerCase.contains("advanced micro devices")

  /** A `warp` is an NVidia term used to describe the set of threads that share
    * a program counter and thus move together in the execution pipeline.  Reads
    * and writes of shared memory by same-warp threads need not be synchronized.
    * The reduce kernels use knowledge of the `warpSize` to avoid unnecessary
    * performance-reducing synchronizations.  NVidia will likely stick with 32
    * as its warp size for the foreseeable future.  CPU platforms (at least
    * for non-integrated graphics processors) should use 1 as the warp size.
    * @return  The number of threads in a `warp`
    */
  private def warpSize = if (isNVidia) 32 else 1

  /** What is the least capable device on this platform w.r.t. localMemSize?
    * We now prune off GPU devices that have less local memory than others
    * in the platform, figuring those are the "low-end" monitor cards that
    * shouldn't be used for compute.
    */
  private def localMemSize: Long =
    devices.map(_.clDevice.getLocalMemSize).foldLeft(64*1024L)(_ min _)

  /** What is the least capable device on this platform w.r.t. constant memory
    * allocations?  OpenCL specs this at a minimum of 64K, but that seems to
    * be all that NVidia and others provide.  Just in case we're on a CPU
    * platform with more memory, we will allow the max to grow to 256KBytes.
    */
  private def maxConstantBufferSize: Long =
    devices.map(_.clDevice.getMaxConstantBufferSize).foldLeft(256*1024L)(_ min _)

  /** What is the least capable device on this platform w.r.t. individual buffer
    * allocations?
    */
  def maxFieldSize: Long =
    devices.map(_.clDevice.getMaxMemAllocSize).foldLeft(Long.MaxValue)(_ min _)

  /** A bundle of platform parameters that affect kernel code generation and optimization. */
  def platformParams =
    OpenCLPlatformParams(
      maxConstantBufferSize,
      localMemSize,
      warpSize,
      isNVidia)
  /** Print out a string describing the platform. */
  override def toString = platformToString(platform)

  /** Function for getting around an apparent bug in AMD's implementation of
    * clWaitForEvents (though it could be a bug in JOCL instead).
    */
  def waitForEvents(events: Seq[CLEvent]): Unit = {
    if (isAMD) {
      // Wait for each event one at a time. AMD is rumored to have problems
      // if this is not done.
      for (event <- events)
        if (event != null)
          (new CLEventList(CLEventFactory, event)).waitForEvents
        else
          println("WARNING: null event found on event list !!!")
    } else {
      // Wait for all events (spec says this is legal).
      if (events.length > 0) {
        val eventList = new CLEventList(CLEventFactory, events: _*)
        eventList.waitForEvents
      }
    }
  }

  /** Create devices for the platform. selecting only GPU devices
    * if any are available, else all devices.
    */
  private def createDevices(): Array[OpenCLDevice] = {
    // Select devices
    val allDevices = platform.listCLDevices()
    val gpuDevices = platform.listCLDevices(GPU)
    val selectedDevices =
      if (gpuDevices.length > 0) {
        val maxLocalMemSize = gpuDevices.map(_.getLocalMemSize).foldLeft(0L)(_ max _)
        val bestGPUDevices = gpuDevices.filter(_.getLocalMemSize == maxLocalMemSize)
        bestGPUDevices
      }
      else
        allDevices
    if (Cog.verboseOpenCLDevices) {
      val ignoredDevices = allDevices.filterNot(selectedDevices.contains(_))
      selectedDevices.foreach(device => println("Selecting: " + device.toString))
      ignoredDevices.foreach(device => println("Ignoring: " + device.toString))
    }

    // Create a single context all devices.
    clContext_ = CLContext.create(selectedDevices :_*)

    // Create the simplified OpenCLDevices.
    Array.tabulate(selectedDevices.length) {
      i => new OpenCLDevice(selectedDevices(i), this, Cog.profile)
    }
  }

  /** Select the OpenCL Platform for this node. */
  private def selectPlatform(): CLPlatform = {
    val selectedPlatform = bestPlatform
    require(selectedPlatform != null, "No OpenCL platforms found")
    if (Cog.verboseOpenCLPlatform) {
      val platforms: Array[CLPlatform] = CLPlatform.listCLPlatforms
      println("JOCL Version:\n"+ JOCLVersion.getVersion())
      println("Selecting OpenCL platform: " + platformToString(selectedPlatform))
      platforms.foreach(platform =>
        if (platform != selectedPlatform)
          println("Ignoring OpenCL platform: " + platformToString(platform) )
      )
    }
    selectedPlatform
  }

  /** Get the "best" CLPlatform, defined as the latest-rev OpenCL Platform with GPUs
    * if one exists, else the latest-rev OpenCL Platform of any kind.
    */
  private def bestPlatform: CLPlatform = {

    // User can set platform filter via JVM runtime arg, e.g. -Dcog.platformFilter=amd
    // If this is not done, then preferredPlatform = null and the default
    // approach of selecting a GPU platform over any CPU one is followed.

    // Also supporting an legacy name for the parameter, namely cog.platform
    val preferredPlatform =
      if (Cog.platformFilter != null)
        Cog.platformFilter
      else
        java.lang.System.getProperty("cog.platform")

    val platformFilter = new Filter[CLPlatform] {
      def accept(platform: CLPlatform): Boolean = {
        if (preferredPlatform == null)
          true
        else {
          platformToString(platform).toLowerCase.contains(preferredPlatform.toLowerCase)
        }
      }
    }
    // Note CLPlatformFilters.type method conflicts with Scala type keyword,
    // so we must use back-ticks.
    val gpuPlatform = CLPlatform.getDefault(`type`(GPU))
    if (preferredPlatform == null && gpuPlatform != null)
      gpuPlatform
    else
      CLPlatform.getDefault(platformFilter)
  }
  
  private def platformToString(p: CLPlatform) = "  " + p.getVendor + " " +
    p.getVersion + " with extensions " + p.getExtensions

}

/** Factory method to create OpenCLPlatforms.  Each platform instance has its own OpenCL context, with
  * devices with their own command queues.  This permits multiple compute graphs to operate independently
  * on the same hardware (assuming global memory is not exhausted).
  */
object OpenCLPlatform {

  // No need to eagerly init the Jogamp JOCL framework- each platform construction ensures the init.
  // CLPlatform.initialize()

  private lazy val _descriptor = {
    val tempPlatform = new OpenCLPlatform()
    val ret = tempPlatform.platformDescriptor
    tempPlatform.release()
    ret
  }

  /** A description of the platform (with vendor info, OpenCL version, etc.). */
  def descriptor = _descriptor

  /** Create a platform instance able to control the OpenCL devices independently of other platforms. */
  def apply(): OpenCLPlatform = new OpenCLPlatform()
}

