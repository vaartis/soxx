package soxx.scrappers

import scala.concurrent._

import play.api.inject.Injector
import xml.Elem
import play.api.libs.json._

class MoebooruScrapper(
  name: String,
  baseUrl: String,
  favicon: String,

  injector: Injector
) extends GenericScrapper(name, baseUrl, favicon, injector)
    with traits.XMLImageCount {

  case class MoebooruImage(
    id: Int,
    height: Int,
    width: Int,
    score: Int,
    file_url: String,
    preview_url: String,
    tags: String,
    md5: String,
    // Some do provide it, some do not. Yande.re has it,
    // but konachan does not, although the API otherwise seems to be the same.
    file_ext: Option[String]
  )

  // There doesn't seem to be a real limit,
  // so just set it to 10
  override val maxPageFetchingConcurrency = 10
  override val maxImageFetchingConcurrency = 10

  override type ScrapperImage = MoebooruImage

  override implicit val imageFormat = Json.format[MoebooruImage]

  override val apiAddition = "post"

  override val imageCountAddition = f"${apiAddition}.xml"

  override def extractImageCount(from: Elem) =
    (from \\ "posts" \ "@count").map{ _.text }.head.toInt

  override def getPageImagesAndCurrentPage(currentPage: Int): Future[(Seq[MoebooruImage], Int)] =
    ws
      .url(s"${baseUrl}/${apiAddition}.json")
      .addQueryStringParameters(
        ("limit", pageSize.toString),
        ("page", currentPage.toString),
      )
      .get()
      .map { res => (res.json.as[Seq[MoebooruImage]], currentPage) }

  override def scrapperImageToImage(img: MoebooruImage): Option[Image] = {
    import java.net.URI
    import java.nio.file.Paths

    import io.scalaland.chimney.dsl._

    // getPath is important here, because without it Paths.get hangs
    val fileName = Paths.get(new URI(img.file_url).getPath).getFileName.toString

    Some(
      img.into[Image]
        .withFieldComputed(_.tags, _.tags.split(" ").toSeq)
        .withFieldComputed(
          _.extension,
          _.file_ext.map(e => f".$e").getOrElse(fileName.substring(fileName.lastIndexOf('.')))
        )
        .withFieldConst(
          _.from,
          Seq(
          From(
            id = img.id,
            name = name,
            score = img.score,
            post = f"${baseUrl}/post/show/${img.id}",
            image = img.file_url,
            thumbnail = img.preview_url
          )
          )
        )
        .transform
    )
  }
}
