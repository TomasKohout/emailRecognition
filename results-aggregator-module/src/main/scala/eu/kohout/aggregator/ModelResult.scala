package eu.kohout.aggregator

case class ModelResult(
  result: Int,
  typeOfModel: ModelType) {
  override def toString: String = s"""
                                     |{
                                     |  "typeOfModel": "${this.typeOfModel.toString}",
                                     |  "result": ${this.result}
                                     |}""".stripMargin
}
