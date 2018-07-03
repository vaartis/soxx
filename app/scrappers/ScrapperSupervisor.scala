package soxx.scrappers

import javax.inject._
import scala.util._

import akka.actor._
import scala.concurrent._
import play.api.libs.ws._
import play.api.inject.ApplicationLifecycle
import play.api.Logger
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
    lifecycle: ApplicationLifecycle
  ) extends Actor {

  override val supervisorStrategy = (new StoppingSupervisorStrategy).create()
  val logger = Logger(this.getClass)

  /* Reads the `scrappers.toml` configuration file and start the scrappers defined.
   *
   * Additional documentation about defining scrappers can be
   * found in the aforementioned file.
   */
  def startScrappersFromConfig() {
    Try {
      val cfgFile = scala.io.Source.fromFile("scrappers.toml")
      try cfgFile.mkString finally cfgFile.close
    } match {
      case Success(configStr) =>
        Toml.parseAsValue[Map[String, ScrapperConfig]](configStr) match {
          case Right(config) =>
            config.foreach { case (name, config) =>
              if (config.enabled) {
                val scrapperType = config.`type` match {
                  case "old-danbooru" => classOf[OldDanbooruScrapper]
                  case "new-danbooru" => classOf[NewDanbooruScrapper]
                  case "moebooru" => classOf[MoebooruScrapper]
                }

                context.actorOf(
                  Props(
                    scrapperType,
                    name,
                    config.`base-url`,
                    config.favicon,
                    implicitly[WSClient],
                    implicitly[Mongo],
                    implicitly[ExecutionContext]
                  ),
                  f"$name-scrapper"
                )
              }
            }
          case Left(pE) => logger.error(f"Error reading scrapper config file: $pE")
        }
      case Failure(e) => logger.error(f"Error opening scrapper config file: $e")
    }
  }

  override def preStart {
    mongo.db
      .getCollection("images")
      .createIndexes(
        Seq(
          IndexModel(Document("from" -> 1)),
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
