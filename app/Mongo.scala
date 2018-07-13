package soxx.mongowrapper

import javax.inject._

import play.api.Configuration
import org.mongodb.scala.MongoClient
import org.mongodb.scala.bson.codecs.Macros._
import org.mongodb.scala.bson.codecs.DEFAULT_CODEC_REGISTRY
import org.bson.codecs.configuration.CodecRegistries.{fromRegistries, fromProviders}

import play.api.inject.ApplicationLifecycle
import scala.concurrent.{ExecutionContext, Future}

import soxx.scrappers._

@Singleton
class Mongo @Inject() (implicit lifecycle: ApplicationLifecycle, ec: ExecutionContext, config: Configuration) {
  val client: MongoClient = MongoClient(config.get[String]("soxx.mongo.connectionString"))

  val codecRegistry =
    fromRegistries(
      fromProviders(
        classOf[Image],
        classOf[BoardInfo],
        classOf[From]
      ),
      DEFAULT_CODEC_REGISTRY
    )

  lazy val db = client.getDatabase(config.get[String]("soxx.mongo.dbName")).withCodecRegistry(codecRegistry)

  lifecycle.addStopHook { () =>
    Future { client.close() }
  }
}
