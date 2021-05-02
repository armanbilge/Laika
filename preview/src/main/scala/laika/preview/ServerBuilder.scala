/*
 * Copyright 2012-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package laika.preview

import java.io.File

import cats.syntax.all._
import cats.effect._
import laika.ast
import laika.ast.DocumentType
import laika.format.{EPUB, PDF}
import laika.io.api.TreeParser
import laika.io.model.InputTreeBuilder
import laika.preview.ServerBuilder.Logger
import org.http4s.HttpApp
import org.http4s.implicits._
import org.http4s.server.{Router, Server}
import org.http4s.server.blaze.BlazeServerBuilder

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/** Configures and instantiates a resource for a preview server.
  * 
  * Any of the provided inputs which originate in the file system will be watched,
  * and any change will trigger a new transformation.
  * Other input types, such as those generated in memory, will require creating and launching
  * a new server instance.
  * 
  * @author Jens Halm
  */
class ServerBuilder[F[_]: Async] (parser: Resource[F, TreeParser[F]],
                                  inputs: InputTreeBuilder[F],
                                  logger: Option[Logger[F]], 
                                  config: ServerConfig) {

  private def copy (newLogger: Option[Logger[F]] = logger,
                    newConfig: ServerConfig = config): ServerBuilder[F] =
    new ServerBuilder[F](parser, inputs, newLogger, newConfig)
  
  private val staticFiles: Option[StaticFileScanner] =
    config.targetDir.map(dir => new StaticFileScanner(dir, config.apiFiles.nonEmpty))
  
  private def createSourceChangeWatcher (cache: Cache[F, SiteResults[F]],
                                         docTypeMatcher: ast.Path => DocumentType): Resource[F, Unit] =
    SourceChangeWatcher.create(inputs.fileRoots.toList, cache.update, config.pollInterval, inputs.exclude, docTypeMatcher)
    
  private def createApp (cache: Cache[F, SiteResults[F]]): HttpApp[F] = {
    val routeLogger =
      if (config.isVerbose) logger.getOrElse((s: String) => Async[F].delay(println(s)))
      else (s: String) => Async[F].unit
    Router("/" -> new RouteBuilder[F](cache, routeLogger).build).orNotFound
  }
    
  private def createServer (httpApp: HttpApp[F],
                            ctx: ExecutionContext): Resource[F, Server] =
    BlazeServerBuilder[F](ctx)
      .bindHttp(config.port, "localhost")
      .withHttpApp(httpApp)
      .resource
  
  private def binaryRenderFormats =
    List(EPUB).filter(_ => config.includeEPUB) ++
    List(PDF).filter(_ => config.includePDF)

  private[preview] def buildRoutes: Resource[F, HttpApp[F]] = for {
    transf <- SiteTransformer.create(parser, inputs, binaryRenderFormats, staticFiles, config.pollInterval, config.artifactBasename)
    cache  <- Resource.eval(Cache.create(transf.transform))
    _      <- createSourceChangeWatcher(cache, transf.parser.config.docTypeMatcher)
  } yield createApp(cache)
  
  def build: Resource[F, Server] = for {
    routes <- buildRoutes
    ctx    <- Resource.eval(Async[F].executionContext)
    server <- createServer(routes, ctx)
  } yield server

  def withLogger (logger: Logger[F]): ServerBuilder[F] = copy(newLogger = Some(logger))
  def withConfig (config: ServerConfig): ServerBuilder[F] = copy(newConfig = config)
  
}

/** Companion for creating a builder for a preview server.
  */
object ServerBuilder {
  
  type Logger[F[_]] = String => F[Unit]

  /** Creates a new builder for a preview server based on the provided parser and inputs.
    * Further aspects like theme, port, poll interval and other details can optionally be configured
    * with the API of the returned instance.
    */
  def apply[F[_]: Async](parser: Resource[F, TreeParser[F]], inputs: InputTreeBuilder[F]): ServerBuilder[F] = 
    new ServerBuilder[F](parser, inputs, None, ServerConfig.defaults)
  
}

/** Additional configuration options for a preview server.
  * 
  * @param port the port the server should run on (default 4242)
  * @param pollInterval the interval at which input file resources are polled for changes (default 3 seconds)
  * @param artifactBasename the base name for PDF and EPUB artifacts linked by the generated site (default "docs")
  * @param includeEPUB indicates whether EPUB downloads should be included on a download page (default false)
  * @param includePDF indicates whether PDF downloads should be included on a download page (default false)
  * @param isVerbose whether each served page and each detected file change should be logged (default false)
  * @param targetDir an optional target directory from which existing documents like API documentation or older versions 
  *                  of the site can be served (default None)
  * @param apiFiles list of paths for generated API documentation (relative to the transformers virtual root)
  */
class ServerConfig private (val port: Int,
                            val pollInterval: FiniteDuration,
                            val artifactBasename: String,
                            val includeEPUB: Boolean,
                            val includePDF: Boolean,
                            val isVerbose: Boolean,
                            val targetDir: Option[File],
                            val apiFiles: Seq[String]) {

  private def copy (newPort: Int = port,
                    newPollInterval: FiniteDuration = pollInterval,
                    newArtifactBasename: String = artifactBasename,
                    newIncludeEPUB: Boolean = includeEPUB,
                    newIncludePDF: Boolean = includePDF,
                    newVerbose: Boolean = isVerbose,
                    newTargetDir: Option[File] = targetDir,
                    newApiFiles: Seq[String] = apiFiles): ServerConfig =
    new ServerConfig(newPort, newPollInterval, newArtifactBasename, newIncludeEPUB, newIncludePDF, newVerbose, newTargetDir, newApiFiles)

  /** Specifies the port the server should run on (default 4242).
    */
  def withPort (port: Int): ServerConfig = copy(newPort = port)

  /** Specifies the interval at which input file resources are polled for changes (default 3 seconds).
    */
  def withPollInterval (interval: FiniteDuration): ServerConfig = copy(newPollInterval = interval)

  /** Indicates that EPUB downloads should be included on the download page.
    */
  def withEPUBDownloads: ServerConfig = copy(newIncludeEPUB = true)

  /** Indicates that PDF downloads should be included on the download page.
    */
  def withPDFDownloads: ServerConfig = copy(newIncludePDF = true)

  /** Specifies the base name for PDF and EPUB artifacts linked by the generated site (default "docs").
    * Additional classifiers might be added to the base name (apart from the file suffix), depending on configuration.
    */
  def withArtifactBasename (name: String): ServerConfig = copy(newArtifactBasename = name)

  /** Specifies a target directory from which existing documents like API documentation or older versions
    * of the site can be served.
    */
  def withTargetDirectory (dir: File): ServerConfig = copy(newTargetDir = Some(dir))

  /** Specifies a list of paths containing generated API documentation (relative to the transformers virtual root).
    */
  def withApiFiles (apiFiles: Seq[String]): ServerConfig = copy(newApiFiles = apiFiles)

  /** Indicates that each served page and each detected file change should be logged to the console.
    */
  def verbose: ServerConfig = copy(newVerbose = true)
  
}

/** Companion for preview server configuration instances.
  */
object ServerConfig {

  val defaultPort: Int = 4242

  val defaultPollInterval: FiniteDuration = 3.seconds

  val defaultArtifactBasename: String = "docs"

  /** A ServerConfig instance populated with default values. */
  val defaults = new ServerConfig(defaultPort, defaultPollInterval, defaultArtifactBasename, false, false, false, None, Nil)
  
}
