package eu.kohout.aggregator

case class Model(
  percent: Int,
  typeOfModel: ModelType) {
  override def toString: String = s"""
                                     |{
                                     |  "typeOfModel": "${this.typeOfModel.toString}",
                                     |  "result": "${this.percent}"
                                     |}""".stripMargin
}
