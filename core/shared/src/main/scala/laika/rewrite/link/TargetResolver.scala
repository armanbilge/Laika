/*
 * Copyright 2012-2020 the original author or authors.
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

package laika.rewrite.link

import laika.ast._


/** Represents the source of a link, its document path
  * and the actual inline span that is representing the link. 
  */
case class LinkSource (span: Span, path: Path)

/** Represents a resolver for a target that has its final identifier generated
  * (if necessary) and can be used to resolve matching reference nodes.
  *
  * TODO - more detail
  *
  * @param selector the selector to use to identify reference nodes matching this target 
  * @param precedence the precedence in comparison to other resolvers with the same selector
  */
abstract sealed class TargetResolver (val selector: Selector, val precedence: Int = 0) {

  /** Creates the final link element for the specified reference
    *  pointing to this target. In case this target does not know
    *  how to resolve the element it should return `None`.
    *
    *  @param linkSource the source of the link
    */
  def resolveReference (linkSource: LinkSource): Option[Span]

  /** Creates the final target element (with its final, resolved identifiers).
    *
    *  @param rewrittenOriginal the original target node in the raw document, potentially
    *  already rewritten in case any of its children got rewritten
    */
  def replaceTarget (rewrittenOriginal: Customizable): Option[Customizable]

}

object ReferenceResolver {
  def lift(f: PartialFunction[LinkSource, Span]): LinkSource => Option[Span] = f.lift
  def internalLink (target: Path): LinkSource => Option[Span] = lift {
    case LinkSource(InternalReference(content, _, _, title, opt), sourcePath) =>
      val relPath =
        if (sourcePath == target.withoutFragment) target.relativeTo(sourcePath)
        else target.relativeTo(sourcePath.parent)
      SpanLink(content, InternalTarget(target, relPath), title, opt)
  }
}

object TargetReplacer {
  def lift(f: PartialFunction[Block, Block]): Block => Option[Block] = f.lift
  def addId(id: String): Block => Option[Block] = block => Some(block.withId(id))
  val removeId: Block => Option[Block] = block => Some(block.withoutId)
  val removeTarget: Block => Option[Block] = Function.const(None)
}

object TargetResolver {

  def create(selector: Selector,
             referenceResolver: LinkSource => Option[Span],
             targetResolver: Block => Option[Block],
             precedence: Int = 0): TargetResolver = new TargetResolver(selector, precedence) {

    override def resolveReference (linkSource: LinkSource): Option[Span] = referenceResolver(linkSource)

    override def replaceTarget (rewrittenOriginal: Customizable): Option[Customizable] = rewrittenOriginal match {
      case b: Block => targetResolver(b)
      case _ => None
    }
  }

  def forSpanTarget(idSelector: TargetIdSelector,
                    referenceResolver: LinkSource => Option[Span]): TargetResolver = new TargetResolver(idSelector) {

    override def resolveReference (linkSource: LinkSource): Option[Span] = referenceResolver(linkSource)

    override def replaceTarget (rewrittenOriginal: Customizable): Option[Customizable] = rewrittenOriginal match {
      case s: Span => Some(s.withId(idSelector.id))
      case _ => None
    }
  }

  def forInvalidTarget (selector: UniqueSelector, msg: String): TargetResolver = new TargetResolver(selector) {
    val sysMsg: SystemMessage = SystemMessage(MessageLevel.Error, msg)
    val resolver = ReferenceResolver.lift { case LinkSource(ref: Reference, _) => InvalidElement(msg, ref.source).asSpan }

    override def resolveReference (linkSource: LinkSource): Option[Span] = resolver(linkSource)

    override def replaceTarget (rewrittenOriginal: Customizable): Option[Customizable] = rewrittenOriginal match {
      case b: Block => Some(InvalidBlock(sysMsg, b.withoutId))
      case s: Span => Some(InvalidSpan(sysMsg, s.withoutId))
      case _ => None
    }
  }

  def forDuplicateSelector (selector: UniqueSelector, path: Path, targets: Seq[TargetResolver]): TargetResolver = {
    val sorted = targets.sortBy(_.precedence).reverse
    sorted.takeWhile(_.precedence == sorted.head.precedence) match {
      case Seq(single) => single
      case _ => forInvalidTarget(selector, s"More than one ${selector.description} in path $path")
    }
  }

  def forDelegate (selector: Selector, delegate: TargetResolver): TargetResolver = new TargetResolver(selector) {
    override def resolveReference (linkSource: LinkSource) = delegate.resolveReference(linkSource)
    override def replaceTarget (rewrittenOriginal: Customizable) = delegate.replaceTarget(rewrittenOriginal)
  }

}

/** Represents a resolver for a sequence of targets where matching reference nodes
  *  get determined by position. The `resolveReference` and `resolveTarget`
  *  methods can be invoked as many times as this sequence contains elements.
  */
case class TargetSequenceResolver (targets: Seq[TargetResolver], sel: Selector) extends TargetResolver(sel) {
  private val refIt = targets.iterator
  private val targetIt = targets.iterator

  private def nextOption (it: Iterator[TargetResolver]) = if (it.hasNext) Some(it.next) else None

  def resolveReference (linkSource: LinkSource): Option[Span] =
    nextOption(refIt).flatMap(_.resolveReference(linkSource))

  def replaceTarget (rewrittenOriginal: Customizable): Option[Customizable] =
    nextOption(targetIt).flatMap(_.replaceTarget(rewrittenOriginal))
}

case class LinkAliasResolver (sourceSelector: TargetIdSelector,
                              targetSelector: TargetIdSelector,
                              referenceResolver: LinkSource => Option[Span]) extends TargetResolver(sourceSelector) {
  override def resolveReference (linkSource: LinkSource): Option[Span] = referenceResolver(linkSource)
  override def replaceTarget (rewrittenOriginal: Customizable): Option[Customizable] = None

  def resolveWith (referenceResolver: LinkSource => Option[Span]): LinkAliasResolver =
    copy(referenceResolver = referenceResolver)

  def circularReference: LinkAliasResolver = copy(referenceResolver =
    TargetResolver.forInvalidTarget(sourceSelector, s"circular link reference: ${targetSelector.id}").resolveReference)
}
object LinkAliasResolver {
  def unresolved (sourceSelector: TargetIdSelector, targetSelector: TargetIdSelector): LinkAliasResolver =
    apply(sourceSelector, targetSelector,
      TargetResolver.forInvalidTarget(sourceSelector, s"unresolved link alias: ${targetSelector.id}").resolveReference)
}