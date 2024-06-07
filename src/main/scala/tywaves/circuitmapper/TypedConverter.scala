package tywaves.circuitmapper

import chisel3.RawModule
import chisel3.stage.ChiselGeneratorAnnotation
import firrtl.AnnotationSeq
import tywaves.BuildInfo.firtoolBinaryPath

import java.io.{BufferedReader, FileReader}

/** This object exposes the Convert phase used in ChiselStage */
private[tywaves] object TypedConverter {
  private lazy val converter   = new chisel3.stage.phases.Convert
  private lazy val chiselStage = new circt.stage.ChiselStage(withDebug = true)

  private val args = Array("--target", "systemverilog", "--split-verilog", "--firtool-binary-path", firtoolBinaryPath)

  private var hglddDebugDir   = "hgldd/debug"
  private var hglddWithOptDir = "hgldd/opt" // TODO: remove

  // In the default annotations, emit also the debug hgldd file (a json file containing info from the debug dialect)
  private val defaultAnnotations =
    createFirtoolOptions(Seq(
//      "-disable-annotation-unknown",
      "--emit-hgldd",
      "--hgldd-output-prefix=<path>",
      /*,"--output-final-mlir=WORK.mlir"*/
    ))

  private var workingDir: Option[String] = None

  val debugFileExt = ".dd" // extension of the debug file

  private def createFirtoolOptions(args: Seq[String]): AnnotationSeq =
    args.map(circt.stage.FirtoolOption).toSeq

  @deprecated(since = "0.3.0")
  /** This function is used to add the FirrtlIR representation */
  def addFirrtlAnno(annotations: AnnotationSeq): AnnotationSeq =
    converter.transform(annotations)

  /** This function is used to elaborate the circuit and get the ChiselIR */
  def getChiselStageAnno[T <: RawModule](generateModule: () => T, workingDir: String = "workingDir"): AnnotationSeq = {
    this.workingDir = Some(workingDir)
    hglddWithOptDir = workingDir + "/" + hglddWithOptDir
    hglddDebugDir = workingDir + "/" + hglddDebugDir

    val annotations = Seq(ChiselGeneratorAnnotation(generateModule)) ++ defaultAnnotations
    chiselStage.execute(
      args ++ Array("--target-dir", hglddWithOptDir),
      annotations,
    ) // execute returns the passThrough annotations in CIRCT transform stage

    chiselStage.execute(
      args ++ Array("--target-dir", hglddDebugDir),
      annotations ++ Seq(circt.stage.FirtoolOption("-g"), circt.stage.FirtoolOption("-O=debug")),
      // execute returns the passThrough annotations in CIRCT transform stage
    )
  }

  def getDebugIRDir(gOpt: Boolean): String =
    if (gOpt) hglddDebugDir
    else hglddWithOptDir

  @deprecated("This function is not used anymore. It is kept for reference.", "0.3.0")
  /** Get the name of the debug file */
  def getDebugIRFile(gOpt: Boolean, topModuleName: String): String = {

    def getFile(_workingDir: String): String = {
      val workingDir = new java.io.File(_workingDir)
      // Open the HGLDD file and extract the information
      if (workingDir.exists() && workingDir.isDirectory) {
        workingDir.listFiles().filter(_.getName == topModuleName + debugFileExt).head.getAbsolutePath
      } else
        throw new Exception(s"WorkingDir: $workingDir does not exist or is not a directory.")
    }

    if (gOpt) getFile(this.hglddDebugDir)
    else getFile(this.hglddWithOptDir)
  }
}

object GenerateHgldd {

  /**
   * It generates runs the mapper Chisel to Vcd and returns the directory where
   * the HGLDD is dumped.
   */
  def apply[T <: RawModule](generateModule: () => T, workingDir: String = "workingDir"): String = {
    val mapChiselToVcd = new MapChiselToVcd(generateModule, workingDir)("TOP", "TbName", "DutName")
    mapChiselToVcd.dumpLog()
    TypedConverter.getDebugIRFile(gOpt = true, generateModule.toString())
  }
}
