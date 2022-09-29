/*
 * Copyright 2017 Datamountaineer.
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

package com.datamountaineer.streamreactor.connect.mqtt.sink

import com.datamountaineer.kcql.Kcql
import com.datamountaineer.streamreactor.common.converters.{FieldConverter, ToJsonWithProjections}
import com.datamountaineer.streamreactor.common.converters.sink.Converter
import com.datamountaineer.streamreactor.common.errors.ErrorHandler
import com.datamountaineer.streamreactor.connect.mqtt.config.MqttSinkSettings
import com.datamountaineer.streamreactor.connect.mqtt.connection.MqttClientConnectionFn
import com.typesafe.scalalogging.StrictLogging
import org.apache.kafka.connect.sink.SinkRecord
import com.fasterxml.jackson.databind.JsonNode
import org.apache.kafka.connect.data.Schema
import org.eclipse.paho.client.mqttv3.{MqttClient, MqttMessage}
import org.json4s.DefaultFormats
import org.json4s.native.JsonMethods._
import java.nio.ByteBuffer

import scala.collection.JavaConverters._
import scala.util.Try

/**
  * Created by andrew@datamountaineer.com on 27/08/2017.
  * stream-reactor
  */

object MqttWriter {
  def apply(settings: MqttSinkSettings, convertersMap: Map[String, Converter]): MqttWriter = {
    new MqttWriter(MqttClientConnectionFn.apply(settings), settings, convertersMap)
  }
}

class MqttWriter(client: MqttClient, settings: MqttSinkSettings,
                convertersMap: Map[String, Converter])
  extends StrictLogging with ErrorHandler {

  //initialize error tracker
  implicit val formats = DefaultFormats
  initialize(settings.maxRetries, settings.errorPolicy)
  val mappings: Map[String, Set[Kcql]] = settings.kcql.groupBy(k => k.getSource)
  val kcql = settings.kcql
  val msg = new MqttMessage()
  msg.setQos(settings.mqttQualityOfService)
  var mqttTarget : String = ""
  var transformed : JsonNode = null

  def write(records: Set[SinkRecord]) = {

    val grouped = records.groupBy(r => r.topic())

    val t = Try(grouped.map({
      case (topic, records) =>
        //get the kcql statements for this topic
        val kcqls: Set[Kcql] = mappings.get(topic).get
        kcqls.map(k => {
          //for all the records in the group transform
          records.map(r => {
            val converter = convertersMap.getOrElse(k.getSource, null)
            var valueSchema: Schema = null
            var value: Array[Byte] = null
            // Perform this check first to determine if the value schema is bytes
            if (converter != null) {
              val converted_record = converter.convert(mqttTarget, r)
              valueSchema = converted_record.valueSchema()
              value = converted_record.value().asInstanceOf[Array[Byte]]
            }

            // Only transform to Json if the value schema isn't bytes
            if (valueSchema != Schema.BYTES_SCHEMA) {
              transformed = ToJsonWithProjections(
                k.getFields.asScala.map(FieldConverter.apply),
                k.getIgnoredFields.asScala.map(FieldConverter.apply),
                r.valueSchema(),
                r.value(),
                k.hasRetainStructure
              )
            }

            if (converter == null) {
              value = transformed.toString.getBytes()
            }

            //get kafka message key if asked for
            if (valueSchema != Schema.BYTES_SCHEMA && Option(k.getDynamicTarget).getOrElse("").nonEmpty) {
              var mqtttopic = (parse(transformed.toString) \ k.getDynamicTarget).extractOrElse[String](null)
              if (mqtttopic.nonEmpty) {
                mqttTarget = mqtttopic
              }
            } else if (k.getTarget == "_key") {
              mqttTarget = r.key().toString()
            } else {
              mqttTarget = k.getTarget
            }

            (mqttTarget, value)
          }).map(
            {
              case (t, value) => {
                msg.setPayload(value)
                msg.setRetained(settings.mqttRetainedMessage);

                client.publish(t, msg)

              }
            }
          )
        })
    }))

    //handle any errors according to the policy.
    handleTry(t)
  }

  def flush = {}

  def close = {
    client.disconnectForcibly(5000, 5000, false)
    client.close()
  }
}
