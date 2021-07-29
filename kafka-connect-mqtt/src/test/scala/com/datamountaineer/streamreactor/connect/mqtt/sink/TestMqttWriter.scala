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

import com.datamountaineer.streamreactor.common.converters.sink.{BytesConverter, Converter}
import com.datamountaineer.streamreactor.connect.mqtt.MqttTestContainer
import com.datamountaineer.streamreactor.connect.mqtt.config.{MqttConfigConstants, MqttSinkConfig, MqttSinkSettings}
import com.typesafe.scalalogging.StrictLogging
import org.apache.avro.generic.{GenericData, GenericDatumWriter, GenericRecord}
import org.apache.avro.io.EncoderFactory
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.config.ConfigException
import org.apache.kafka.common.record.TimestampType
import org.apache.kafka.connect.data.{Schema, SchemaBuilder, Struct}
import org.apache.kafka.connect.sink.SinkRecord
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence
import org.eclipse.paho.client.mqttv3.{IMqttDeliveryToken, MqttCallback, MqttClient, MqttConnectOptions, MqttMessage}

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Paths
import java.util
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.{Failure, Success, Try}

/**
  * Created by andrew@datamountaineer.com on 27/08/2017.
  * stream-reactor
  */
class TestMqttWriter extends MqttTestContainer with MqttCallback with StrictLogging {

  val mqttUser = "user"
  val mqttPassword = "passwd"

  val queue = new LinkedBlockingQueue[MqttMessage](10)

  val JSON = "{\"id\":\"kafka_topic2-12-1\",\"int_field\":12,\"long_field\":12,\"string_field\":\"foo\",\"float_field\":0.1,\"float64_field\":0.199999,\"boolean_field\":true,\"byte_field\":\"Ynl0ZXM=\"}"
  val JSON_IGNORE_ID = "{\"id\":\"kafka_topic2-12-1\"}}"
  val TOPIC = "kafka_topic"
  val TOPIC2 = "kafka_topic2"
  val TARGET = "mqtt_topic"
  val TARGET2 = "mqtt_topic2"
  protected val PARTITION: Int = 12
  protected val PARTITION2: Int = 13
  protected val TOPIC_PARTITION: TopicPartition = new TopicPartition(TOPIC, PARTITION)
  protected val TOPIC_PARTITION2: TopicPartition = new TopicPartition(TOPIC, PARTITION2)
  protected val TOPIC_PARTITION3: TopicPartition = new TopicPartition(TOPIC2, PARTITION)
  protected val ASSIGNMENT: util.Set[TopicPartition] = new util.HashSet[TopicPartition]
  //Set topic assignments
  ASSIGNMENT.add(TOPIC_PARTITION)
  ASSIGNMENT.add(TOPIC_PARTITION2)
  ASSIGNMENT.add(TOPIC_PARTITION3)


  private def withSubscribedMqttClient(fun: => Any): Unit = {
    val conOpt = new MqttConnectOptions
    conOpt.setCleanSession(true)
    conOpt.setUserName(mqttUser)
    conOpt.setPassword(mqttPassword.toCharArray)

    val dataStore = new MqttDefaultFilePersistence(System.getProperty("java.io.tmpdir"))

    val client = new MqttClient(getMqttConnectionUrl, UUID.randomUUID().toString, dataStore)
    client.setCallback(this)
    client.connect(conOpt)
    client.subscribe(TARGET)

    fun

    client.disconnect()
    client.close()
  }

  def createSinkRecord(record: Struct, topic: String, offset: Long): SinkRecord = {
    new SinkRecord(topic, 1, Schema.STRING_SCHEMA, "key", record.schema(), record, offset, System.currentTimeMillis(), TimestampType.CREATE_TIME)
  }

  //build a test record schema
  def createSchema: Schema = {
    SchemaBuilder.struct.name("record")
      .version(1)
      .field("id", Schema.STRING_SCHEMA)
      .field("int_field", Schema.INT32_SCHEMA)
      .field("long_field", Schema.INT64_SCHEMA)
      .field("string_field", Schema.STRING_SCHEMA)
      .field("float_field", Schema.FLOAT32_SCHEMA)
      .field("float64_field", Schema.FLOAT64_SCHEMA)
      .field("boolean_field", Schema.BOOLEAN_SCHEMA)
      .field("byte_field", Schema.BYTES_SCHEMA)
      .build
  }

  //build a test record
  def createRecord(schema: Schema, id: String): Struct = {
    new Struct(schema)
      .put("id", id)
      .put("int_field", 12)
      .put("long_field", 12L)
      .put("string_field", "foo")
      .put("float_field", 0.1.toFloat)
      .put("float64_field", 0.199999)
      .put("boolean_field", true)
      .put("byte_field", ByteBuffer.wrap("bytes".getBytes))
  }

  //generate some test records
  def getTestRecords: Set[SinkRecord] = {
    val schema = createSchema
    val assignment: mutable.Set[TopicPartition] = getAssignment.asScala

    assignment.flatMap(a => {
      (1 to 1).map(i => {
        val record: Struct = createRecord(schema, a.topic() + "-" + a.partition() + "-" + i)
        new SinkRecord(a.topic(), a.partition(), Schema.STRING_SCHEMA, "key", schema, record, i, System.currentTimeMillis(), TimestampType.CREATE_TIME)
      })
    }).toSet
  }

  //generate some test byte array records
  def getTestByteArrayRecords: Set[SinkRecord] = {
    val assignment: mutable.Set[TopicPartition] = getAssignment.asScala

    assignment.flatMap(a => {
      (1 to 1).map(i => {
        val byteArray = "bytes".getBytes
        val record = ByteBuffer.wrap(byteArray)
        logger.info(s"Bytes: $byteArray, Record: $record")
        new SinkRecord(a.topic(), a.partition(), Schema.STRING_SCHEMA, "key", Schema.BYTES_SCHEMA, byteArray, i, System.currentTimeMillis(), TimestampType.CREATE_TIME)
      })
    }).toSet
  }

  // create a avro record of the test data
  def createAvroRecord(avro_schema_file: String): Array[Byte] = {

    // Avro schema
    val avro_schema_source = scala.io.Source.fromFile(avro_schema_file).mkString
    val avro_schema = new org.apache.avro.Schema.Parser().parse(avro_schema_source)

    // Generic Record
    val genericValues = new GenericData.Record(avro_schema)
    genericValues.put("id", "kafka_topic2-12-1")
    genericValues.put("int_field", 12)
    genericValues.put("long_field", 12L)
    genericValues.put("string_field", "foo")
    genericValues.put("float_field", 0.1.toFloat)
    genericValues.put("float64_field", 0.199999)
    genericValues.put("boolean_field", true)
    genericValues.put("byte_field", ByteBuffer.wrap("bytes".getBytes))

    // Serialize Generic Record
    val out = new ByteArrayOutputStream()
    val encoder = EncoderFactory.get().binaryEncoder(out, null)
    val avro_writer = new GenericDatumWriter[GenericRecord](avro_schema)

    avro_writer.write(genericValues, encoder)
    encoder.flush()
    out.close()

    out.toByteArray
  }

  //get the assignment of topic partitions for the sinkTask
  def getAssignment: util.Set[TopicPartition] = {
    ASSIGNMENT
  }

  "writer should write bytes with byte array converter" in {
    val props = Map(
      MqttConfigConstants.HOSTS_CONFIG -> getMqttConnectionUrl,
      MqttConfigConstants.KCQL_CONFIG -> s"INSERT INTO $TARGET SELECT * FROM $TOPIC WITHCONVERTER=`${classOf[BytesConverter].getCanonicalName}`;INSERT INTO $TARGET SELECT * FROM $TOPIC2 WITHCONVERTER=`${classOf[BytesConverter].getCanonicalName}`",
      MqttConfigConstants.QS_CONFIG -> "1",
      MqttConfigConstants.CLEAN_SESSION_CONFIG -> "true",
      MqttConfigConstants.CLIENT_ID_CONFIG -> UUID.randomUUID().toString,
      MqttConfigConstants.CONNECTION_TIMEOUT_CONFIG -> "1000",
      MqttConfigConstants.KEEP_ALIVE_INTERVAL_CONFIG -> "1000",
      MqttConfigConstants.PASSWORD_CONFIG -> mqttPassword,
      MqttConfigConstants.USER_CONFIG -> mqttUser
    )

    val config = MqttSinkConfig(props.asJava)
    val settings = MqttSinkSettings(config)

    val convertersMap = settings.sinksToConverters.map { case (topic, clazz) =>
        logger.info(s"Creating converter instance for $clazz and $topic")
        if (clazz == null) {
          topic -> null
        } else {
          val converter = Try(Class.forName(clazz).newInstance()) match {
            case Success(value) => value.asInstanceOf[Converter]
            case Failure(_) => throw new ConfigException(s"Invalid ${MqttConfigConstants.KCQL_CONFIG} is invalid. $clazz should have an empty ctor!")
          }
          converter.initialize(props)
          topic -> converter
        }
      }

    val writer = MqttWriter(settings, convertersMap)
    val records = getTestByteArrayRecords

    withSubscribedMqttClient {
      writer.write(records)
      Thread.sleep(2000)

      queue.size() shouldBe 3
      val message = queue.asScala.take(1).head.getPayload
      queue.clear()
      logger.info(s"Message: $message")
      message shouldBe a [Array[Byte]]
      val stringMessage = new String(message) 
      stringMessage shouldBe "bytes"
      writer.close
    }
  }

  "writer should writer all fields" in {
    val props = Map(
      MqttConfigConstants.HOSTS_CONFIG -> getMqttConnectionUrl,
      MqttConfigConstants.KCQL_CONFIG -> s"INSERT INTO $TARGET SELECT * FROM $TOPIC;INSERT INTO $TARGET SELECT * FROM $TOPIC2",
      MqttConfigConstants.QS_CONFIG -> "1",
      MqttConfigConstants.CLEAN_SESSION_CONFIG -> "true",
      MqttConfigConstants.CLIENT_ID_CONFIG -> UUID.randomUUID().toString,
      MqttConfigConstants.CONNECTION_TIMEOUT_CONFIG -> "1000",
      MqttConfigConstants.KEEP_ALIVE_INTERVAL_CONFIG -> "1000",
      MqttConfigConstants.PASSWORD_CONFIG -> mqttPassword,
      MqttConfigConstants.USER_CONFIG -> mqttUser
    )

    val config = MqttSinkConfig(props.asJava)
    val settings = MqttSinkSettings(config)

    val convertersMap = settings.sinksToConverters.map { case (topic, clazz) =>
        logger.info(s"Creating converter instance for $clazz and $topic")
        if (clazz == null) {
          topic -> null
        } else {
          val converter = Try(Class.forName(clazz).newInstance()) match {
            case Success(value) => value.asInstanceOf[Converter]
            case Failure(_) => throw new ConfigException(s"Invalid ${MqttConfigConstants.KCQL_CONFIG} is invalid. $clazz should have an empty ctor!")
          }
          converter.initialize(props)
          topic -> converter
        }
      }

    val writer = MqttWriter(settings, convertersMap)
    val records = getTestRecords

    withSubscribedMqttClient {
      writer.write(records)
      Thread.sleep(2000)

      queue.size() shouldBe 3
      val message = new String(queue.asScala.take(1).head.getPayload)
      queue.clear()
      message shouldBe JSON
      writer.close
    }
  }

  "writer should writer all fields in avro" in {

    val res = getClass.getClassLoader.getResource("test.avsc")
    val schemaPath = Paths.get(res.toURI).toFile.getAbsolutePath

    val sinkAvroSchemas = s"kafka_topic=$schemaPath;kafka_topic2=$schemaPath"

    val props = Map(
      MqttConfigConstants.HOSTS_CONFIG -> getMqttConnectionUrl,
      MqttConfigConstants.KCQL_CONFIG -> s"INSERT INTO $TARGET SELECT * FROM $TOPIC WITHCONVERTER=`com.datamountaineer.streamreactor.common.converters.sink.AvroConverter` WITHTARGET=string_field;INSERT INTO $TARGET SELECT * FROM $TOPIC2 WITHCONVERTER=`com.datamountaineer.streamreactor.common.converters.sink.AvroConverter` WITHTARGET=string_field",
      MqttConfigConstants.QS_CONFIG -> "1",
      MqttConfigConstants.AVRO_CONVERTERS_SCHEMA_FILES -> sinkAvroSchemas,
      MqttConfigConstants.CLEAN_SESSION_CONFIG -> "true",
      MqttConfigConstants.CLIENT_ID_CONFIG -> UUID.randomUUID().toString,
      MqttConfigConstants.CONNECTION_TIMEOUT_CONFIG -> "1000",
      MqttConfigConstants.KEEP_ALIVE_INTERVAL_CONFIG -> "1000",
      MqttConfigConstants.PASSWORD_CONFIG -> mqttPassword,
      MqttConfigConstants.USER_CONFIG -> mqttUser
    ).asJava

    val config = MqttSinkConfig(props)
    val settings = MqttSinkSettings(config)

    val convertersMap = settings.sinksToConverters.map { case (topic, clazz) =>
      logger.info(s"Creating converter instance for $clazz")
      val converter = Try(Class.forName(clazz).newInstance()) match {
        case Success(value) => value.asInstanceOf[Converter]
        case Failure(_) => throw new ConfigException(s"Invalid ${MqttConfigConstants.KCQL_CONFIG} is invalid. $clazz should have an empty ctor!")
      }

      converter.initialize(props.asScala.toMap)
      topic -> converter
    }

    val writer = MqttWriter(settings, convertersMap)
    val records = getTestRecords

    withSubscribedMqttClient {
      writer.write(records)
      Thread.sleep(2000)

      queue.size() shouldBe 3

      val message = createAvroRecord(s"$schemaPath")

      queue.asScala.take(1).head.getPayload shouldBe message
      queue.clear()
      writer.close
    }
  }

  override def messageArrived(topic: String, message: MqttMessage): Unit = {
    logger.info(s"Received message on topic $topic")
    queue.put(message)
  }

  override def deliveryComplete(token: IMqttDeliveryToken): Unit = {}

  override def connectionLost(cause: Throwable): Unit = {}
}
