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

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream}

import cats.syntax.all._
import cats.effect.{Async, Resource}
import laika.api.Renderer
import laika.api.builder.OperationConfig
import laika.ast.{DocumentTreeRoot, Path}
import laika.config.Config.ConfigResult
import laika.config.{ConfigException, LaikaKeys}
import laika.factory.{BinaryPostProcessorBuilder, TwoPhaseRenderFormat}
import laika.format.{EPUB, HTML, PDF}
import laika.io.api.{BinaryTreeRenderer, TreeParser, TreeRenderer}
import laika.io.config.SiteConfig
import laika.io.implicits._
import laika.io.model.{InputTreeBuilder, ParsedTree, StringTreeOutput}
import laika.rewrite.nav.Selections
import laika.theme.ThemeProvider

class SiteTransformer[F[_]: Async] (val parser: TreeParser[F], 
                                    htmlRenderer: TreeRenderer[F],
                                    epubRenderer: BinaryTreeRenderer[F],
                                    pdfRenderer: BinaryTreeRenderer[F],
                                    inputs: InputTreeBuilder[F],
                                    artifactBasename: String) {

  private val parse = {
    //        val apiPath = validated(SiteConfig.apiPath(baseConfig))
    //        val inputs = generateAPI.value.foldLeft(laikaInputs.value.delegate) {
    //          (inputs, path) => inputs.addProvidedPath(apiPath / path)
    //        }
    parser.fromInput(inputs).parse
  }
  
  def renderBinary (renderer: BinaryTreeRenderer[F], root: DocumentTreeRoot): Resource[F, InputStream] = {
    val renderResult = for {
      out <- Async[F].delay(new ByteArrayOutputStream)
      _   <- renderer.from(root).toStream(Async[F].pure(out)).render
    } yield out.toByteArray
    
    Resource
      .eval(renderResult)
      .flatMap(bytes => Resource.eval(Async[F].delay(new ByteArrayInputStream(bytes))))
  }
  
  def transformBinaries (root: DocumentTreeRoot): ConfigResult[Map[Path, SiteResult[F]]] = {
    for {
      roots            <- Selections.createCombinations(root)
      downloadPath     <- SiteConfig.downloadPath(root.config)
      artifactBaseName <- root.config.get[String](LaikaKeys.artifactBaseName, artifactBasename)
    } yield {
      val combinations = 
        roots.map(r => (r._1, r._2, epubRenderer, ".epub")) ++
        roots.map(r => (r._1, r._2, pdfRenderer, ".pdf"))
      combinations.map { case (root, classifiers, renderer, suffix) =>
        val classifier = if (classifiers.value.isEmpty) "" else "-" + classifiers.value.mkString("-")
        val docName = artifactBaseName + classifier + suffix
        val path = downloadPath / docName
        (path, StaticResult(renderBinary(renderer, root)))
      }.toList.toMap
    }
  }

  def transformHTML (tree: ParsedTree[F]): F[Map[Path, SiteResult[F]]] = {
    htmlRenderer
      .from(tree.root)
      .copying(tree.staticDocuments)
      .toOutput(StringTreeOutput)
      .render
      .map { root =>
        val map = root.allDocuments.map { doc =>
          (doc.path, RenderedResult[F](doc.content))
        }.toMap ++
        root.staticDocuments.map { doc =>
          (doc.path, StaticResult(doc.input))
        }.toMap
        val roots = map.flatMap { case (path, result) =>
          if (path.name == "index.html") Some((path.parent, result)) else None 
        }
        map ++ roots
      }
  }

  val transform: F[SiteResults[F]] = for { 
    tree     <- parse
    rendered <- transformHTML(tree)
    ebooks   <- Async[F].fromEither(transformBinaries(tree.root).leftMap(ConfigException.apply))
  } yield {
    new SiteResults(rendered ++ ebooks)
  }
  
}

object SiteTransformer {

  def htmlRenderer[F[_]: Async] (config: OperationConfig, 
                                 theme: ThemeProvider): Resource[F, TreeRenderer[F]] = 
    Renderer
      .of(HTML)
      .withConfig(config)
      .parallel[F]
      .withTheme(theme)
      .build
  
  def binaryRenderer[F[_]: Async, FMT] (format: TwoPhaseRenderFormat[FMT, BinaryPostProcessorBuilder],
                                        config: OperationConfig,
                                        theme: ThemeProvider): Resource[F, BinaryTreeRenderer[F]] = 
    Renderer
      .of(format)
      .withConfig(config)
      .parallel[F]
      .withTheme(theme)
      .build

  def create[F[_]: Async](parser: Resource[F, TreeParser[F]],
                          inputs: InputTreeBuilder[F],
                          theme: ThemeProvider,
                          artifactBasename: String): Resource[F, SiteTransformer[F]] = {
    for {
      p      <- parser
      html   <- htmlRenderer(p.config, theme)
      epub   <- binaryRenderer(EPUB, p.config, theme)
      pdf    <- binaryRenderer(PDF, p.config, theme)
    } yield new SiteTransformer[F](p, html, epub, pdf, inputs, artifactBasename)
    
  }
  
}

class SiteResults[F[_]: Async] (map: Map[Path, SiteResult[F]]) {
  
  def get (path: Path): Option[SiteResult[F]] = map.get(path)
  
}

sealed abstract class SiteResult[F[_]: Async] extends Product with Serializable
case class RenderedResult[F[_]: Async] (content: String) extends SiteResult[F]
case class StaticResult[F[_]: Async] (content: Resource[F, InputStream]) extends SiteResult[F]
