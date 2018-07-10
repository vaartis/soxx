package soxx.scrappers

import javax.inject._
import scala.util._
import scala.util.control.NonFatal

import akka.actor._
import scala.concurrent._
import play.api.{Configuration, Logger}
import play.api.inject.{Injector, ApplicationLifecycle}
import play.api.libs.ws._
import toml.Toml
import toml.Codecs._

import org.mongodb.scala._
import org.mongodb.scala.model._

import soxx.mongowrapper._

@Singleton
class ScrapperSupervisor @Inject()
  (
    implicit ec: ExecutionContext,
    ws: WSClient,
    mongo: Mongo,
    lifecycle: ApplicationLifecycle,
    appConfig: Configuration,
    injector: Injector
  ) extends Actor {

  override val supervisorStrategy = (new StoppingSupervisorStrategy).create()
  val logger = Logger(this.getClass)

  /* Reads the `scrappers.toml` configuration file and start the scrappers defined.
   *
   * Additional documentation about defining scrappers can be
   * found in the aforementioned file.
   */
  def startScrappersFromConfig(configPath: String = appConfig.get[String]("soxx.scrappers.configFile")) {
    val configStr =
      // Very ugly, but scala's file IO support is pretty poor..
      try {
        val cfgFile = scala.io.Source.fromFile(configPath)
        try cfgFile.mkString finally cfgFile.close
      } catch {
        case NonFatal(e) =>
          logger.error(f"Error reading scrapper config file ($configPath): $e")
          return
      }

    Toml.parseAsValue[Map[String, ScrapperConfig]](configStr) match {
      case Right(config) =>
        config.foreach { case (name, config) =>
          if (config.enabled) {
            val scrapperType = config.`type` match {
              case "old-danbooru" => classOf[OldDanbooruScrapper]
              case "new-danbooru" => classOf[NewDanbooruScrapper]
              case "moebooru" => classOf[MoebooruScrapper]
              case i =>
                logger.error(f"Unknown type used for imageboard $name: $i, skipping")
                return
            }

            context.actorOf(
              Props(
                scrapperType,
                name,
                config.`base-url`.stripSuffix("/"), // Remove the trailing slash
                config.favicon,
                injector
              ),
              f"$name-scrapper"
            )
          }
        }
      case Left(pE) => logger.error(f"Error reading scrapper config file: $pE")
    }
  }

  override def preStart {
    mongo.db
      .getCollection("images")
      .createIndexes(
        Seq(
          IndexModel(Document("from.name" -> 1)),
          IndexModel(Document("md5" -> 1))
        )
      )
      .subscribe(
        (_: String) => {
          logger.info(f"Created/Updated image indexes")
        }
      )

    startScrappersFromConfig()

    lifecycle.addStopHook { () =>
      Future { context.stop(self) }
    }
  }

  override def receive = {
    case _ => ???
  }
}
