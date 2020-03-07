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

import laika.ast.{Span, _}

/** Representations for various types of link targets.
 * 
 *  @author Jens Halm
 */
object LinkTargets {

  /** Represents a selector used for matching reference
   *  nodes to target nodes. The selectors often differ
   *  from both, the ids rendered in the final document
   *  and the ids used for display.
   */
  sealed trait Selector {

    /** Indicates whether this selector is applicable
      * beyond the boundaries of a single document.
      */
    def global: Boolean

    /** Indicates whether this selector has to be unique
      * within its scope.
      * 
      * When the global flag is set it must be globally
      * unique, otherwise it must only be unique within
      * the document it occurs in.
      */
    def unique: Boolean
    
  }

  /** A selector that can be used for a sequence of targets.
    */
  sealed trait SequenceSelector extends Selector {
    val global = false
    val unique = false
  }

  /** A selector that can is a globally unique identifier.
    */
  sealed trait UniqueSelector extends Selector {
    val global = true
    val unique = true
    def description: String
  }

  /** A selector for a rendered target in a document.
   */
  case class TargetIdSelector (id: String) extends UniqueSelector {
    val description = s"link target with id '$id'"
  }

  /** A selector for a definition for an internal or external link.
    */
  case class LinkDefinitionSelector (id: String) extends UniqueSelector {
    val description = s"link definition with id '$id'"
  }
  
  /** A selector based on a path, optionally including a fragment component.
    */
  case class PathSelector (path: Path) extends UniqueSelector {
    val description = s"link target with path '$path'"
  }
  
  /** An anonymous selector (usually matched by position).
   */
  case object AnonymousSelector extends SequenceSelector
  
  /** An auto-number selector (usually matched by position).
   */
  case object AutonumberSelector extends SequenceSelector
  
  /** An auto-symbol selector (usually matched by position).
   */
  case object AutosymbolSelector extends SequenceSelector


  /** Represents the source of a link, its document path
    * and the actual inline span that is representing the link. 
    */
  case class LinkSource (span: Span, path: Path)

  // TODO - 0.15 - move
  def slug (text: String): String = {
    text.replaceAll("[^a-zA-Z0-9-]+","-").replaceFirst("^-","").replaceFirst("-$","").toLowerCase // TODO - retain unicode characters
  }
  
//  class LinkAliasTarget (alias: LinkAlias) extends TargetDefinition(alias, NamedX(alias.id), false) {
//    
//    def withResolvedIds (documentId: String, displayId: String): SingleTargetResolver = 
//      SingleTargetResolver(this, UniqueSelector(alias.id), Hidden)
//    
//    val resolve: ((Span,String)) => Option[Span] = lift (PartialFunction.empty)
//    val ref: String = alias.target
//    val from: String = alias.id
//  }
  
  /** Represents a resolver for a target that has its final identifier generated
    * (if necessary) and can be used to resolve matching reference nodes.
    * 
    * TODO - more detail
    *  
    * @param selector the selector to use to identify reference nodes matching this target 
    */
  abstract sealed class TargetResolver (val selector: Selector) {
    
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
    def replaceTarget (rewrittenOriginal: Block): Option[Block]
    
  }
  
  object ReferenceResolver {
    def lift(f: PartialFunction[LinkSource, Span]): LinkSource => Option[Span] = f.lift
    def forDuplicateTargetId(selector: UniqueSelector, path: Path): LinkSource => Option[Span] = {
      val msg = s"More than one ${selector.description} in path $path"
      lift { case LinkSource(ref: Reference, _) => InvalidElement(msg, ref.source).asSpan }
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
               targetResolver: Block => Option[Block]): TargetResolver = new TargetResolver(selector) {

      override def resolveReference (linkSource: LinkSource): Option[Span] = referenceResolver(linkSource)
      override def replaceTarget (rewrittenOriginal: Block): Option[Block] = targetResolver(rewrittenOriginal)
    }
    
  }
  
//  case class SingleTargetResolver (target: TargetDefinition, selector: Selector, render: String, forAlias: Boolean = false) extends TargetResolver {
//    
//    def replaceTarget (rewrittenOriginal: Element): Option[Element] = if (forAlias) None else target.replace(rewrittenOriginal, render)
//    
//    def forAlias (newSelector: Selector): SingleTargetResolver = SingleTargetResolver(target, newSelector, render, forAlias = true)
  
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

    def replaceTarget (rewrittenOriginal: Block): Option[Block] = 
      nextOption(targetIt).flatMap(_.replaceTarget(rewrittenOriginal))
  }
  
  
}