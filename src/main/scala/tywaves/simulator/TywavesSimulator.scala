package tywaves.simulator

import chisel3.RawModule
import chisel3.simulator.PeekPokeAPI
import svsim.Workspace
import tywaves.circuitmapper.TypedConverter

object TywavesSimulator extends PeekPokeAPI {

  private[simulator] case class Tywaves(runWaves: Boolean, waitFor: Boolean) extends SimulatorSettings

  /** Generate tywaves info and optionally run the waveform */
  val WithTywavesWaveforms: Boolean => Tywaves = (runWaves: Boolean) => Tywaves(runWaves, waitFor = true)

  /**
   * Generate tywaves info and optionally run the waveform without blocking sbt
   */
  val WithTywavesWaveformsGo: Boolean => Tywaves = (runWaves: Boolean) => Tywaves(runWaves, waitFor = false)

  /** Use this method to run a simulation */
  def simulate[T <: RawModule](
      module:   => T,
      settings: Seq[SimulatorSettings] = Seq(),
      simName:  String = "defaultSimulation",
  )(body: T => Unit): Unit = {

    // Create a new simulator instance
    val simulator = new TywavesSimulator

    val containTywaves = settings.exists(_.isInstanceOf[Tywaves])

    val finalSettings =
      if (containTywaves)
        settings ++ Seq(FirtoolArgs(TypedConverter.firtoolBaseArgs))
      // Seq(FirtoolArgs(Seq("-O=debug", "-g", "--emit-hgldd", "--split-verilog", "-o=WORK.v")))
      else settings

    simulator.simulate(module, finalSettings, simName)(body)

    if (simulator.finalTracePath.nonEmpty && containTywaves) {
      // Get the extra scopes created by ChiselSim backend: TOP, svsimTestbench, dut
      val extraScopes = Seq("TOP", Workspace.testbenchModuleName, "dut")

      // Create the debug info from the firtool and get the top module name
      // TODO: this may not be needed anymore, since the debug info can be generated directly from chiselsim, by giving the right options to firtool
      // But the problem is to call chiselstage with debug options
      TypedConverter.createDebugInfoHgldd(
        () => module,
        workingDir = simulator.wantedWorkspacePath,
        additionalFirtoolArgs = simulator.getFirtoolArgs,
      )

      // Run tywaves viewer if the Tywaves waveform generation is enabled by Tywaves(true)
      val (runWaves, waitFor) =
        if (finalSettings.contains(Tywaves(runWaves = true, waitFor = true))) { (true, true) }
        else if (finalSettings.contains(Tywaves(runWaves = true, waitFor = false))) { (true, false) }
        else { (false, false) }
      if (runWaves)
        TywavesInterface.run(
          vcdPath = simulator.finalTracePath.get,
          hglddDirPath = Some(TypedConverter.getDebugInfoDir(gOpt = true)),
          extraScopes = extraScopes,
          topModuleName = TypedConverter.getTopModuleName,
          waitFor = waitFor,
        )
    } else if (containTywaves)
      throw new Exception("Tywaves waveform generation requires a trace file. Please enable VcdTrace.")

  }

}
class TywavesSimulator extends ParametricSimulator
