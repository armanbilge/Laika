package laika.preview

import java.io.InputStream

import cats.syntax.all._
import cats.effect.{Async, Resource, Sync}
import fs2.io.readInputStream
import laika.preview.ServerBuilder.Logger
import org.http4s.EntityEncoder.entityBodyEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.{CacheDirective, EntityEncoder, Headers, HttpRoutes, MediaType}
import org.http4s.headers.{`Cache-Control`, `Content-Type`}

private [preview] class RouteBuilder[F[_]: Async](cache: Cache[F, SiteResults[F]], logger: Logger[F]) extends Http4sDsl[F] {

  implicit def inputStreamResourceEncoder[G[_]: Sync, IS <: InputStream]: EntityEncoder[G, Resource[G, IS]] =
    entityBodyEncoder[G].contramap { (in: Resource[G, IS]) =>
      fs2.Stream.resource(in).flatMap { stream =>
        readInputStream[G](Sync[G].pure(stream), 4096, closeAfterUse = false)
      }
    }
  
  private def mediaTypeFor (suffix: String): Option[MediaType] =
    MediaType.forExtension(suffix).orElse(MediaType.forExtension(suffix.split("\\.").last))
  
  private val noCache = `Cache-Control`(CacheDirective.`no-store`)
  
  def build: HttpRoutes[F] = HttpRoutes.of[F] {

    case GET -> path =>
      val laikaPath = laika.ast.Path.parse(path.toString)
      cache.get.map(_.get(laikaPath.withoutFragment)).flatMap {
        case Some(RenderedResult(content)) =>
          logger(s"serving path $laikaPath - transformed markup") *>
          Ok(content).map(_
            .withHeaders(noCache)
            .withContentType(`Content-Type`(MediaType.text.html))
          )
        case Some(StaticResult(input)) =>
          logger(s"serving path $laikaPath - static input") *> {
            val mediaType = laikaPath.suffix.flatMap(mediaTypeFor).map(`Content-Type`(_))
            Ok(input).map(_.withHeaders(Headers(mediaType, noCache)))
          }
        case None =>
          logger(s"serving path $laikaPath - not found") *> NotFound()
      }
  }
  
}