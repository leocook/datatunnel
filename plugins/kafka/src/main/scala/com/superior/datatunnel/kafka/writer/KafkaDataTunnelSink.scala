package com.superior.datatunnel.kafka.writer

import com.superior.datatunnel.api.{DataTunnelSink, DataTunnelSinkContext}
import com.superior.datatunnel.kafka.KafkaSinkOption
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.spark.sql.{Dataset, Row}

import scala.collection.JavaConverters._
import scala.util.parsing.json.JSONObject

/**
 * huaixin 2021/12/7 8:12 PM
 */
class KafkaDataTunnelSink extends DataTunnelSink[KafkaSinkOption] {

  override def sink(dataset: Dataset[Row], context: DataTunnelSinkContext[KafkaSinkOption]): Unit = {
    val topic = context.getSinkOption.getTopic
    val options = context.getSinkOption.getParams

    options.put("key.serializer", classOf[StringSerializer].getName)
    options.put("value.serializer", classOf[StringSerializer].getName)

    val map = options.asScala.filter{ case (key, _) => !key.startsWith("__") && key != "topic" }
    val config = collection.immutable.Map(map.toSeq: _*)
    dataset.writeToKafka(
      config,
      row => new ProducerRecord[String, String](topic, convertRowToJSON(row))
    )
  }

  def convertRowToJSON(row: Row): String = {
    if (row.schema.fieldNames.length == 1) {
      row.getString(0)
    } else {
      val m = row.getValuesMap(row.schema.fieldNames)
      JSONObject(m).toString()
    }
  }
}
