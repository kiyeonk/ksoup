package com.fleeksoft.ksoup.parser

import com.fleeksoft.ksoup.helper.Validate
import com.fleeksoft.ksoup.internal.Normalizer
import com.fleeksoft.ksoup.internal.StringUtil
import com.fleeksoft.ksoup.nodes.CDataNode
import com.fleeksoft.ksoup.nodes.Comment
import com.fleeksoft.ksoup.nodes.DataNode
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.FormElement
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode
import com.fleeksoft.ksoup.parser.HtmlTreeBuilderState.Constants.InTableFoster
import com.fleeksoft.ksoup.parser.HtmlTreeBuilderState.ForeignContent
import com.fleeksoft.ksoup.parser.Parser.Companion.NamespaceHtml
import com.fleeksoft.ksoup.parser.Token.StartTag
import com.fleeksoft.ksoup.ported.BufferReader
import com.fleeksoft.ksoup.ported.assert
import kotlin.jvm.JvmOverloads

/**
 * HTML Tree Builder; creates a DOM from Tokens.
 */
internal open class HtmlTreeBuilder : TreeBuilder() {
    private var state: HtmlTreeBuilderState? = null // the current state
    private var originalState: HtmlTreeBuilderState? = null // original / marked state
    private var baseUriSetFromDoc = false

    private var headElement: Element? = null // the current head element

    private var formElement: FormElement? = null // the current form element

    private var contextElement: Element? =
        null // fragment parse context -- could be null even if fragment parsing
    private var formattingElements: ArrayList<Element?>? =
        null // active (open) formatting elements
    private var tmplInsertMode: ArrayList<HtmlTreeBuilderState>? =
        null // stack of Template Insertion modes
    private var pendingTableCharacters: MutableList<Token.Character>? =
        null // chars in table to be shifted out
    private var emptyEnd: Token.EndTag? = null // reused empty end tag
    private var framesetOk = false // if ok to go into frameset
    var isFosterInserts = false // if next inserts should be fostered
    var isFragmentParsing = false // if parsing a fragment of html
        private set

    override fun defaultSettings(): ParseSettings? {
        return ParseSettings.htmlDefault
    }

    override fun newInstance(): HtmlTreeBuilder {
        return HtmlTreeBuilder()
    }

    override fun parseFragment(
        inputFragment: String,
        context: Element?,
        baseUri: String?,
        parser: Parser,
    ): List<Node> {
        // context may be null
        state = HtmlTreeBuilderState.Initial
        initialiseParse(BufferReader(inputFragment), baseUri, parser)
        contextElement = context
        isFragmentParsing = true
        var root: Element? = null
        if (context != null) {
            if (context.ownerDocument() != null) {
                // quirks setup:
                doc.quirksMode(context.ownerDocument()!!.quirksMode())
            }

            // initialise the tokeniser state:
            val contextTag: String = context.normalName()
            when (contextTag) {
                "title", "textarea" -> tokeniser!!.transition(TokeniserState.Rcdata)
                "iframe", "noembed", "noframes", "style", "xmp" ->
                    tokeniser!!.transition(
                        TokeniserState.Rawtext,
                    )

                "script" -> tokeniser!!.transition(TokeniserState.ScriptData)
                "plaintext" -> tokeniser!!.transition(TokeniserState.PLAINTEXT)
                "template" -> {
                    tokeniser!!.transition(TokeniserState.Data)
                    pushTemplateMode(HtmlTreeBuilderState.InTemplate)
                }

                else -> tokeniser!!.transition(TokeniserState.Data)
            }
            root = Element(tagFor(contextTag, settings), baseUri)
            doc.appendChild(root)
            stack.add(root)
            resetInsertionMode()

            // setup form element to nearest form on context (up ancestor chain). ensures form controls are associated
            // with form correctly
            var formSearch: Element? = context
            while (formSearch != null) {
                if (formSearch is FormElement) {
                    formElement = formSearch
                    break
                }
                formSearch = formSearch.parent()
            }
        }
        runParser()
        return if (context != null) {
            // depending on context and the input html, content may have been added outside of the root el
            // e.g. context=p, input=div, the div will have been pushed out.
            val nodes: List<Node> = root!!.siblingNodes()
            if (nodes.isNotEmpty()) root.insertChildren(-1, nodes)
            root.childNodes()
        } else {
            doc.childNodes()
        }
    }

    override fun process(token: Token): Boolean {
        currentToken = token
        return if (shouldDispatchToCurrentInsertionMode(token)) {
            state!!.process(token, this)
        } else {
            ForeignContent.process(token, this)
        }
    }

    override fun initialiseParse(
        input: BufferReader,
        baseUri: String?,
        parser: Parser,
    ) {
        super.initialiseParse(input, baseUri, parser)

        // this is a bit mucky. todo - probably just create new parser objects to ensure all reset.
        state = HtmlTreeBuilderState.Initial
        originalState = null
        baseUriSetFromDoc = false
        headElement = null
        formElement = null
        contextElement = null
        formattingElements = ArrayList<Element?>()
        tmplInsertMode = ArrayList<HtmlTreeBuilderState>()
        pendingTableCharacters = ArrayList<Token.Character>()
        emptyEnd = Token.EndTag()
        framesetOk = true
        isFosterInserts = false
        isFragmentParsing = false
    }

    fun shouldDispatchToCurrentInsertionMode(token: Token): Boolean {
        // https://html.spec.whatwg.org/multipage/parsing.html#tree-construction
        // If the stack of open elements is empty
        if (stack.isEmpty()) return true
        val el: Element = currentElement()
        val ns: String = el.tag().namespace()

        // If the adjusted current node is an element in the HTML namespace
        if (NamespaceHtml == ns) return true

        // If the adjusted current node is a MathML text integration point and the token is a start tag whose tag name is neither "mglyph" nor "malignmark"
        // If the adjusted current node is a MathML text integration point and the token is a character token
        if (isMathmlTextIntegration(el)) {
            if (token.isStartTag() &&
                "mglyph" != token.asStartTag().normalName &&
                "malignmark" != token.asStartTag().normalName
            ) {
                return true
            }
            if (token.isCharacter()) return true
        }
        // If the adjusted current node is a MathML annotation-xml element and the token is a start tag whose tag name is "svg"
        if (Parser.NamespaceMathml == ns &&
            el.normalName() == "annotation-xml" &&
            token.isStartTag() && "svg" == token.asStartTag().normalName
        ) {
            return true
        }

        // If the adjusted current node is an HTML integration point and the token is a start tag
        // If the adjusted current node is an HTML integration point and the token is a character token
        return if (isHtmlIntegration(el) &&
            (token.isStartTag() || token.isCharacter())
        ) {
            true
        } else {
            token.isEOF()
        }

        // If the token is an end-of-file token
    }

    fun isMathmlTextIntegration(el: Element): Boolean {
        /*
        A node is a MathML text integration point if it is one of the following elements:
        A MathML mi element
        A MathML mo element
        A MathML mn element
        A MathML ms element
        A MathML mtext element
         */
        return (
            Parser.NamespaceMathml == el.tag().namespace() &&
                StringUtil.inSorted(el.normalName(), TagMathMlTextIntegration)
        )
    }

    fun isHtmlIntegration(el: Element): Boolean {
        /*
        A node is an HTML integration point if it is one of the following elements:
        A MathML annotation-xml element whose start tag token had an attribute with the name "encoding" whose value was an ASCII case-insensitive match for the string "text/html"
        A MathML annotation-xml element whose start tag token had an attribute with the name "encoding" whose value was an ASCII case-insensitive match for the string "application/xhtml+xml"
        An SVG foreignObject element
        An SVG desc element
        An SVG title element
         */
        if (Parser.NamespaceMathml == el.tag().namespace() &&
            el.normalName() == "annotation-xml"
        ) {
            val encoding: String = Normalizer.normalize(el.attr("encoding"))
            if (encoding == "text/html" || encoding == "application/xhtml+xml") return true
        }
        return Parser.NamespaceSvg == el.tag().namespace() &&
            StringUtil.isIn(
                el.tagName(),
                *TagSvgHtmlIntegration,
            )
    }

    fun process(
        token: Token,
        state: HtmlTreeBuilderState,
    ): Boolean {
        currentToken = token
        return state.process(token, this)
    }

    fun transition(state: HtmlTreeBuilderState?) {
        this.state = state
    }

    fun state(): HtmlTreeBuilderState? {
        return state
    }

    fun markInsertionMode() {
        originalState = state
    }

    fun originalState(): HtmlTreeBuilderState? {
        return originalState
    }

    fun framesetOk(framesetOk: Boolean) {
        this.framesetOk = framesetOk
    }

    fun framesetOk(): Boolean {
        return framesetOk
    }

    val document: Document
        get() = doc

    fun maybeSetBaseUri(base: Element) {
        if (baseUriSetFromDoc) {
            // only listen to the first <base href> in parse
            return
        }
        val href: String = base.absUrl("href")
        if (href.isNotEmpty()) { // ignore <base target> etc
            baseUri = href
            baseUriSetFromDoc = true
            doc.setBaseUri(href) // set on the doc so doc.createElement(Tag) will get updated base, and to update all descendants
        }
    }

    fun error(state: HtmlTreeBuilderState?) {
        if (parser!!.getErrors().canAddError()) {
            parser!!.getErrors().add(
                ParseError(
                    reader,
                    "Unexpected ${currentToken!!.tokenType()} token [$currentToken] when in state [$state]",
                ),
            )
        }
    }

    /** Inserts an HTML element for the given tag)  */
    fun insert(startTag: Token.StartTag): Element {
        dedupeAttributes(startTag)

        // handle empty unknown tags
        // when the spec expects an empty tag, will directly hit insertEmpty, so won't generate this fake end tag.
        if (startTag.isSelfClosing) {
            val el: Element = insertEmpty(startTag)
            stack.add(el)
            tokeniser!!.transition(TokeniserState.Data) // handles <script />, otherwise needs breakout steps from script data
            tokeniser!!.emit(
                emptyEnd!!.reset().name(el.tagName()),
            ) // ensure we get out of whatever state we are in. emitted for yielded processing
            return el
        }
        val el =
            Element(
                tagFor(startTag.name(), settings),
                null,
                settings!!.normalizeAttributes(startTag.attributes),
            )
        insert(el, startTag)
        return el
    }

    /**
     * Inserts a foreign element. Preserves the case of the tag name and of the attributes.
     */
    fun insertForeign(
        startTag: Token.StartTag,
        namespace: String?,
    ): Element {
        dedupeAttributes(startTag)
        val tag: Tag = tagFor(startTag.name(), namespace!!, ParseSettings.preserveCase)
        val el =
            Element(
                tag,
                null,
                ParseSettings.preserveCase.normalizeAttributes(startTag.attributes),
            )
        insert(el, startTag)
        if (startTag.isSelfClosing) {
            tag.setSelfClosing() // remember this is self-closing for output
            pop()
        }
        return el
    }

    fun insertStartTag(startTagName: String?): Element {
        val el = Element(tagFor(startTagName!!, settings), null)
        insert(el)
        return el
    }

    fun insert(el: Element) {
        insertNode(el, null)
        stack.add(el)
    }

    private fun insert(
        el: Element,
        token: Token,
    ) {
        insertNode(el, token)
        stack.add(el)
    }

    fun insertEmpty(startTag: Token.StartTag): Element {
        dedupeAttributes(startTag)
        val tag: Tag = tagFor(startTag.name(), settings)
        val el = Element(tag, null, settings!!.normalizeAttributes(startTag.attributes))
        insertNode(el, startTag)
        if (startTag.isSelfClosing) {
            if (tag.isKnownTag()) {
                if (!tag.isEmpty) {
                    tokeniser!!.error(
                        "Tag [${tag.normalName()}] cannot be self closing; not a void tag",
                    )
                }
            } else {
                // unknown tag, remember this is self-closing for output
                tag.setSelfClosing()
            }
        }
        return el
    }

    fun insertForm(
        startTag: Token.StartTag,
        onStack: Boolean,
        checkTemplateStack: Boolean,
    ): FormElement {
        dedupeAttributes(startTag)
        val tag: Tag = tagFor(startTag.name(), settings)
        val el = FormElement(tag, null, settings!!.normalizeAttributes(startTag.attributes))
        if (checkTemplateStack) {
            if (!onStack("template")) setFormElement(el)
        } else {
            setFormElement(el)
        }
        insertNode(el, startTag)
        if (onStack) stack.add(el)
        return el
    }

    fun insert(commentToken: Token.Comment) {
        val comment = Comment(commentToken.getData())
        insertNode(comment, commentToken)
    }

    /** Inserts the provided character token into the current element.  */
    fun insert(characterToken: Token.Character) {
        val el: Element =
            currentElement() // will be doc if no current element; allows for whitespace to be inserted into the doc root object (not on the stack)
        insert(characterToken, el)
    }

    /** Inserts the provided character token into the provided element.  */
    fun insert(
        characterToken: Token.Character,
        el: Element,
    ) {
        val node: Node
        val tagName: String = el.normalName()
        val data: String = characterToken.data ?: ""
        node =
            if (characterToken.isCData()) {
                CDataNode(data)
            } else if (isContentForTagData(tagName)) {
                DataNode(
                    data,
                )
            } else {
                TextNode(data)
            }
        el.appendChild(node) // doesn't use insertNode, because we don't foster these; and will always have a stack.
        onNodeInserted(node, characterToken)
    }

    /** Inserts the provided Node into the current element.  */
    private fun insertNode(
        node: Node,
        token: Token?,
    ) {
        // if the stack hasn't been set up yet, elements (doctype, comments) go into the doc
        if (stack.isEmpty()) {
            doc.appendChild(node)
        } else if (isFosterInserts &&
            StringUtil.inSorted(
                currentElement().normalName(),
                InTableFoster,
            )
        ) {
            insertInFosterParent(node)
        } else {
            currentElement().appendChild(node)
        }
        if (node is Element) {
            val el: Element = node
            if (el.tag().isFormListed && formElement != null) {
                formElement?.addElement(el) // connect form controls to their form element
            }

            // in HTML, the xmlns attribute if set must match what the parser set the tag's namespace to
            if (el.hasAttr("xmlns") && el.attr("xmlns") != el.tag().namespace()) {
                error("Invalid xmlns attribute [${el.attr("xmlns")}] on tag [${el.tagName()}]")
            }
        }
        onNodeInserted(node, token)
    }

    /** Cleanup duplicate attributes.  */
    private fun dedupeAttributes(startTag: StartTag) {
        if (startTag.hasAttributes() && !startTag.attributes!!.isEmpty()) {
            val dupes: Int = startTag.attributes!!.deduplicate(settings!!)
            if (dupes > 0) {
                error("Dropped duplicate attribute(s) in tag [${startTag.normalName}]")
            }
        }
    }

    fun pop(): Element? {
        val size: Int = stack.size
        return stack.removeAt(size - 1)
    }

    fun push(element: Element) {
        stack.add(element)
    }

    fun onStack(el: Element): Boolean {
        return onStack(stack, el)
    }

    /** Checks if there is an HTML element with the given name on the stack.  */
    fun onStack(elName: String?): Boolean {
        return getFromStack(elName) != null
    }

    /** Gets the nearest (lowest) HTML element with the given name from the stack.  */

    fun getFromStack(elName: String?): Element? {
        val bottom: Int = stack.size - 1
        val upper = if (bottom >= maxQueueDepth) bottom - maxQueueDepth else 0
        for (pos in bottom downTo upper) {
            val next: Element? = stack[pos]
            if (next?.normalName() == elName && NamespaceHtml == next?.tag()?.namespace()) {
                return next
            }
        }
        return null
    }

    fun removeFromStack(el: Element?): Boolean {
        for (pos in stack.size - 1 downTo 0) {
            val next: Element? = stack[pos]
            if (next === el) {
                stack.removeAt(pos)
                return true
            }
        }
        return false
    }

    /** Pops the stack until the given HTML element is removed.  */

    fun popStackToClose(elName: String?): Element? {
        for (pos in stack.size - 1 downTo 0) {
            val el: Element? = stack[pos]
            stack.removeAt(pos)
            if (el?.normalName() == elName && NamespaceHtml == el?.tag()?.namespace()) {
                if (currentToken is Token.EndTag) onNodeClosed(el, currentToken)
                return el
            }
        }
        return null
    }

    /** Pops the stack until an element with the supplied name is removed, irrespective of namespace.  */

    fun popStackToCloseAnyNamespace(elName: String?): Element? {
        for (pos in stack.size - 1 downTo 0) {
            val el: Element? = stack[pos]
            stack.removeAt(pos)
            if (el?.normalName() == elName) {
                if (currentToken is Token.EndTag) onNodeClosed(el!!, currentToken)
                return el
            }
        }
        return null
    }

    /** Pops the stack until one of the given HTML elements is removed.  */
    fun popStackToClose(vararg elNames: String) { // elnames is sorted, comes from Constants
        for (pos in stack.size - 1 downTo 0) {
            val el: Element? = stack[pos]
            stack.removeAt(pos)
            if (StringUtil.inSorted(
                    el!!.normalName(),
                    elNames.toList().toTypedArray(),
                ) && NamespaceHtml == el.tag().namespace()
            ) {
                if (currentToken is Token.EndTag) onNodeClosed(el, currentToken)
                break
            }
        }
    }

    fun clearStackToTableContext() {
        clearStackToContext("table", "template")
    }

    fun clearStackToTableBodyContext() {
        clearStackToContext("tbody", "tfoot", "thead", "template")
    }

    fun clearStackToTableRowContext() {
        clearStackToContext("tr", "template")
    }

    /** Removes elements from the stack until one of the supplied HTML elements is removed.  */
    private fun clearStackToContext(vararg nodeNames: String) {
        for (pos in stack.size - 1 downTo 0) {
            val next: Element? = stack[pos]
            if (NamespaceHtml == next?.tag()?.namespace() &&
                (StringUtil.isIn(next.normalName(), *nodeNames) || next.normalName() == "html")
            ) {
                break
            } else {
                stack.removeAt(pos)
            }
        }
    }

    fun aboveOnStack(el: Element): Element? {
        assert(onStack(el))
        for (pos in stack.size - 1 downTo 0) {
            val next: Element? = stack[pos]
            if (next === el) {
                return stack[pos - 1]
            }
        }
        return null
    }

    fun insertOnStackAfter(
        after: Element,
        inEl: Element,
    ) {
        val i: Int = stack.lastIndexOf(after)
        Validate.isTrue(i != -1)
        stack.add(i + 1, inEl)
    }

    fun replaceOnStack(
        out: Element,
        `in`: Element,
    ) {
        replaceInQueue(stack, out, `in`)
    }

    private fun replaceInQueue(
        queue: ArrayList<Element?>,
        out: Element,
        inEl: Element,
    ) {
        val i: Int = queue.lastIndexOf(out)
        Validate.isTrue(i != -1)
        queue[i] = inEl
    }

    /**
     * Reset the insertion mode, by searching up the stack for an appropriate insertion mode. The stack search depth
     * is limited to [.maxQueueDepth].
     * @return true if the insertion mode was actually changed.
     */
    fun resetInsertionMode(): Boolean {
        // https://html.spec.whatwg.org/multipage/parsing.html#the-insertion-mode
        var last = false
        val bottom: Int = stack.size - 1
        val upper = if (bottom >= maxQueueDepth) bottom - maxQueueDepth else 0
        val origState: HtmlTreeBuilderState? = state
        if (stack.size == 0) { // nothing left of stack, just get to body
            transition(HtmlTreeBuilderState.InBody)
        }
        LOOP@ for (pos in bottom downTo upper) {
            var node: Element? = stack[pos]
            if (pos == upper) {
                last = true
                if (isFragmentParsing) node = contextElement
            }
            val name = node?.normalName() ?: ""
            if (NamespaceHtml != node!!.tag().namespace()
            ) {
                continue // only looking for HTML elements here
            }
            when (name) {
                "select" -> {
                    transition(HtmlTreeBuilderState.InSelect)
                    // todo - should loop up (with some limit) and check for table or template hits
                    break@LOOP
                }

                "td", "th" ->
                    if (!last) {
                        transition(HtmlTreeBuilderState.InCell)
                        break@LOOP
                    }

                "tr" -> {
                    transition(HtmlTreeBuilderState.InRow)
                    break@LOOP
                }

                "tbody", "thead", "tfoot" -> {
                    transition(HtmlTreeBuilderState.InTableBody)
                    break@LOOP
                }

                "caption" -> {
                    transition(HtmlTreeBuilderState.InCaption)
                    break@LOOP
                }

                "colgroup" -> {
                    transition(HtmlTreeBuilderState.InColumnGroup)
                    break@LOOP
                }

                "table" -> {
                    transition(HtmlTreeBuilderState.InTable)
                    break@LOOP
                }

                "template" -> {
                    val tmplState: HtmlTreeBuilderState? = currentTemplateMode()
                    Validate.notNull(tmplState, "Bug: no template insertion mode on stack!")
                    transition(tmplState)
                    break@LOOP
                }

                "head" ->
                    if (!last) {
                        transition(HtmlTreeBuilderState.InHead)
                        break@LOOP
                    }

                "body" -> {
                    transition(HtmlTreeBuilderState.InBody)
                    break@LOOP
                }

                "frameset" -> {
                    transition(HtmlTreeBuilderState.InFrameset)
                    break@LOOP
                }

                "html" -> {
                    transition(if (headElement == null) HtmlTreeBuilderState.BeforeHead else HtmlTreeBuilderState.AfterHead)
                    break@LOOP
                }
            }
            if (last) {
                transition(HtmlTreeBuilderState.InBody)
                break
            }
        }
        return state !== origState
    }

    /** Places the body back onto the stack and moves to InBody, for cases in AfterBody / AfterAfterBody when more content comes  */
    fun resetBody() {
        if (!onStack("body")) {
            stack.add(doc.body())
        }
        transition(HtmlTreeBuilderState.InBody)
    }

    // todo: tidy up in specific scope methods
    private val specificScopeTarget = arrayOf("")

    private fun inSpecificScope(
        targetName: String,
        baseTypes: Array<String>,
        extraTypes: Array<String>?,
    ): Boolean {
        specificScopeTarget[0] = targetName
        return inSpecificScope(specificScopeTarget, baseTypes, extraTypes)
    }

    private fun inSpecificScope(
        targetNames: Array<String>,
        baseTypes: Array<String>,
        extraTypes: Array<String>?,
    ): Boolean {
        // https://html.spec.whatwg.org/multipage/parsing.html#has-an-element-in-the-specific-scope
        val bottom: Int = stack.size - 1
        val top = if (bottom > MaxScopeSearchDepth) bottom - MaxScopeSearchDepth else 0
        // don't walk too far up the tree
        for (pos in bottom downTo top) {
            val el: Element? = stack[pos]
            if (el?.tag()?.namespace() != NamespaceHtml) continue
            val elName: String = el.normalName()
            if (StringUtil.inSorted(elName, targetNames)) return true
            if (StringUtil.inSorted(elName, baseTypes)) return false
            if (extraTypes != null && StringUtil.inSorted(elName, extraTypes)) return false
        }
        // Validate.fail("Should not be reachable"); // would end up false because hitting 'html' at root (basetypes)
        return false
    }

    fun inScope(targetNames: Array<String>): Boolean {
        return inSpecificScope(targetNames, TagsSearchInScope, null)
    }

    @JvmOverloads
    fun inScope(
        targetName: String,
        extras: Array<String>? = null,
    ): Boolean {
        return inSpecificScope(
            targetName = targetName,
            baseTypes = TagsSearchInScope,
            extraTypes = extras,
        )
        // todo: in mathml namespace: mi, mo, mn, ms, mtext annotation-xml
        // todo: in svg namespace: forignOjbect, desc, title
    }

    fun inListItemScope(targetName: String): Boolean {
        return inScope(targetName, TagSearchList)
    }

    fun inButtonScope(targetName: String): Boolean {
        return inScope(targetName, TagSearchButton)
    }

    fun inTableScope(targetName: String): Boolean {
        return inSpecificScope(targetName, TagSearchTableScope, null)
    }

    fun inSelectScope(targetName: String): Boolean {
        for (pos in stack.size - 1 downTo 0) {
            val el: Element = stack[pos] ?: continue
            val elName: String = el.normalName()
            if (elName == targetName) return true
            if (!StringUtil.inSorted(elName, TagSearchSelectScope)) {
                // all elements except
                return false
            }
        }
        Validate.fail("Should not be reachable")
        return false
    }

    /** Tests if there is some element on the stack that is not in the provided set.  */
    fun onStackNot(allowedTags: Array<String>): Boolean {
        val bottom: Int = stack.size - 1
        val top = if (bottom > MaxScopeSearchDepth) bottom - MaxScopeSearchDepth else 0
        // don't walk too far up the tree
        for (pos in bottom downTo top) {
            val elName: String = stack[pos]?.normalName() ?: continue
            if (!StringUtil.inSorted(elName, allowedTags)) return true
        }
        return false
    }

    fun setHeadElement(headElement: Element?) {
        this.headElement = headElement
    }

    fun getHeadElement(): Element? {
        return headElement
    }

    fun getFormElement(): FormElement? {
        return formElement
    }

    fun setFormElement(formElement: FormElement?) {
        this.formElement = formElement
    }

    fun resetPendingTableCharacters() {
        pendingTableCharacters = ArrayList<Token.Character>()
    }

    fun getPendingTableCharacters(): List<Token.Character>? {
        return pendingTableCharacters
    }

    fun addPendingTableCharacters(c: Token.Character) {
        // make a clone of the token to maintain its state (as Tokens are otherwise reset)
        val clone: Token.Character = c.clone()
        pendingTableCharacters!!.add(clone)
    }

    /**
     * 13.2.6.3 Closing elements that have implied end tags
     * When the steps below require the UA to generate implied end tags, then, while the current node is a dd element, a dt element, an li element, an optgroup element, an option element, a p element, an rb element, an rp element, an rt element, or an rtc element, the UA must pop the current node off the stack of open elements.
     *
     * If a step requires the UA to generate implied end tags but lists an element to exclude from the process, then the UA must perform the above steps as if that element was not in the above list.
     *
     * When the steps below require the UA to generate all implied end tags thoroughly, then, while the current node is a caption element, a colgroup element, a dd element, a dt element, an li element, an optgroup element, an option element, a p element, an rb element, an rp element, an rt element, an rtc element, a tbody element, a td element, a tfoot element, a th element, a thead element, or a tr element, the UA must pop the current node off the stack of open elements.
     *
     * @param excludeTag If a step requires the UA to generate implied end tags but lists an element to exclude from the
     * process, then the UA must perform the above steps as if that element was not in the above list.
     */
    fun generateImpliedEndTags(excludeTag: String?) {
        while (StringUtil.inSorted(currentElement().normalName(), TagSearchEndTags)) {
            if (excludeTag != null && currentElementIs(excludeTag)) break
            pop()
        }
    }

    /**
     * Pops HTML elements off the stack according to the implied end tag rules
     * @param thorough if we are thorough (includes table elements etc) or not
     */
    @JvmOverloads
    fun generateImpliedEndTags(thorough: Boolean = false) {
        val search = if (thorough) TagThoroughSearchEndTags else TagSearchEndTags
        while (NamespaceHtml == currentElement().tag().namespace() &&
            StringUtil.inSorted(currentElement().normalName(), search)
        ) {
            pop()
        }
    }

    fun closeElement(name: String) {
        generateImpliedEndTags(name)
        if (name != currentElement().normalName()) error(state())
        popStackToClose(name)
    }

    fun isSpecial(el: Element): Boolean {
        // todo: mathml's mi, mo, mn
        // todo: svg's foreigObject, desc, title
        val name: String = el.normalName()
        return StringUtil.inSorted(name, TagSearchSpecial)
    }

    fun lastFormattingElement(): Element? {
        return if ((formattingElements?.size ?: 0) > 0) {
            formattingElements!![formattingElements!!.size - 1]
        } else {
            null
        }
    }

    fun positionOfElement(el: Element?): Int {
        for (i in formattingElements!!.indices) {
            if (el === formattingElements!!.get(i)) return i
        }
        return -1
    }

    fun removeLastFormattingElement(): Element? {
        val size: Int = formattingElements?.size ?: 0
        return if (size > 0) formattingElements!!.removeAt(size - 1) else null
    }

    // active formatting elements
    fun pushActiveFormattingElements(`in`: Element) {
        checkActiveFormattingElements(`in`)
        formattingElements!!.add(`in`)
    }

    fun pushWithBookmark(
        `in`: Element,
        bookmark: Int,
    ) {
        checkActiveFormattingElements(`in`)
        // catch any range errors and assume bookmark is incorrect - saves a redundant range check.
        try {
            formattingElements!!.add(bookmark, `in`)
        } catch (e: IndexOutOfBoundsException) {
            formattingElements!!.add(`in`)
        }
    }

    fun checkActiveFormattingElements(`in`: Element) {
        var numSeen = 0
        val size: Int = formattingElements!!.size - 1
        var ceil = size - maxUsedFormattingElements
        if (ceil < 0) ceil = 0
        for (pos in size downTo ceil) {
            val el: Element = formattingElements?.get(pos) ?: break // marker
            if (isSameFormattingElement(`in`, el)) numSeen++
            if (numSeen == 3) {
                formattingElements!!.removeAt(pos)
                break
            }
        }
    }

    private fun isSameFormattingElement(
        a: Element,
        b: Element,
    ): Boolean {
        // same if: same namespace, tag, and attributes. Element.equals only checks tag, might in future check children
        return a.normalName() == b.normalName() && // a.namespace().equals(b.namespace()) &&
            a.attributes() == b.attributes()
        // todo: namespaces
    }

    fun reconstructFormattingElements() {
        if (stack.size > maxQueueDepth) return
        val last: Element? = lastFormattingElement()
        if (last == null || onStack(last)) return
        var entry: Element? = last
        val size: Int = formattingElements?.size ?: 0
        var ceil = size - maxUsedFormattingElements
        if (ceil < 0) ceil = 0
        var pos = size - 1
        var skip = false
        while (true) {
            if (pos == ceil) { // step 4. if none before, skip to 8
                skip = true
                break
            }
            entry = formattingElements?.get(--pos) // step 5. one earlier than entry
            if (entry == null || onStack(entry)) {
                // step 6 - neither marker nor on stack
                break // jump to 8, else continue back to 4
            }
        }
        while (true) {
            if (!skip) {
                // step 7: on later than entry
                entry = formattingElements?.get(++pos)
            }
            Validate.notNull(entry) // should not occur, as we break at last element

            // 8. create new element from element, 9 insert into current node, onto stack
            skip = false // can only skip increment from 4.
            val newEl =
                Element(tagFor(entry!!.normalName(), settings), null, entry.attributes().clone())
            insert(newEl)

            // 10. replace entry with new entry
            formattingElements?.set(pos, newEl)

            // 11
            if (pos == size - 1) {
                // if not last entry in list, jump to 7
                break
            }
        }
    }

    fun clearFormattingElementsToLastMarker() {
        while (!formattingElements!!.isEmpty()) {
            removeLastFormattingElement() ?: break
        }
    }

    fun removeFromActiveFormattingElements(el: Element) {
        for (pos in formattingElements!!.indices.reversed()) {
            val next: Element? = formattingElements?.get(pos)
            if (next === el) {
                formattingElements!!.removeAt(pos)
                break
            }
        }
    }

    fun isInActiveFormattingElements(el: Element): Boolean {
        return onStack(formattingElements?.mapNotNull { it }?.toList() ?: emptyList(), el)
    }

    fun getActiveFormattingElement(nodeName: String?): Element? {
        for (pos in formattingElements!!.indices.reversed()) {
            val next: Element? = formattingElements?.get(pos)
            if (next == null) {
                // scope marker
                break
            } else if (next.normalName() == nodeName) {
                return next
            }
        }
        return null
    }

    fun replaceActiveFormattingElement(
        out: Element,
        `in`: Element,
    ) {
        replaceInQueue(formattingElements!!, out, `in`)
    }

    fun insertMarkerToFormattingElements() {
        formattingElements?.add(null)
    }

    fun insertInFosterParent(inNode: Node) {
        val fosterParent: Element?
        val lastTable: Element? = getFromStack("table")
        var isLastTableParent = false
        if (lastTable != null) {
            if (lastTable.parent() != null) {
                fosterParent = lastTable.parent()
                isLastTableParent = true
            } else {
                fosterParent = aboveOnStack(lastTable)
            }
        } else { // no table == frag
            fosterParent = stack[0]
        }
        if (isLastTableParent) {
            Validate.notNull(lastTable) // last table cannot be null by this point.
            lastTable!!.before(inNode)
        } else {
            fosterParent!!.appendChild(inNode)
        }
    }

    // Template Insertion Mode stack
    fun pushTemplateMode(state: HtmlTreeBuilderState) {
        tmplInsertMode?.add(state)
    }

    fun popTemplateMode(): HtmlTreeBuilderState? {
        return if (!tmplInsertMode.isNullOrEmpty()) {
            tmplInsertMode?.removeAt(tmplInsertMode!!.size - 1)
        } else {
            null
        }
    }

    fun templateModeSize(): Int {
        return tmplInsertMode?.size ?: 0
    }

    fun currentTemplateMode(): HtmlTreeBuilderState? {
        return if (tmplInsertMode!!.size > 0) tmplInsertMode?.get(tmplInsertMode!!.size - 1) else null
    }

    override fun toString(): String {
        return "TreeBuilder{" +
            "currentToken=" + currentToken +
            ", state=" + state +
            ", currentElement=" + currentElement() +
            '}'
    }

    override fun isContentForTagData(normalName: String): Boolean {
        return normalName == "script" || normalName == "style"
    }

    companion object {
        // tag searches. must be sorted, used in inSorted. HtmlTreeBuilderTest validates they're sorted.
        val TagsSearchInScope: Array<String> = arrayOf<String>("applet", "caption", "html", "marquee", "object", "table", "td", "th")
        val TagSearchList = arrayOf<String>("ol", "ul")
        val TagSearchButton = arrayOf<String>("button")
        val TagSearchTableScope = arrayOf<String>("html", "table")
        val TagSearchSelectScope = arrayOf<String>("optgroup", "option")
        val TagSearchEndTags = arrayOf<String>("dd", "dt", "li", "optgroup", "option", "p", "rb", "rp", "rt", "rtc")
        val TagThoroughSearchEndTags =
            arrayOf<String>(
                "caption",
                "colgroup",
                "dd",
                "dt",
                "li",
                "optgroup",
                "option",
                "p",
                "rb",
                "rp",
                "rt",
                "rtc",
                "tbody",
                "td",
                "tfoot",
                "th",
                "thead",
                "tr",
            )
        val TagSearchSpecial =
            arrayOf<String>(
                "address",
                "applet",
                "area",
                "article",
                "aside",
                "base",
                "basefont",
                "bgsound",
                "blockquote",
                "body",
                "br",
                "button",
                "caption",
                "center",
                "col",
                "colgroup",
                "command",
                "dd",
                "details",
                "dir",
                "div",
                "dl",
                "dt",
                "embed",
                "fieldset",
                "figcaption",
                "figure",
                "footer",
                "form",
                "frame",
                "frameset",
                "h1",
                "h2",
                "h3",
                "h4",
                "h5",
                "h6",
                "head",
                "header",
                "hgroup",
                "hr",
                "html",
                "iframe",
                "img",
                "input",
                "isindex",
                "li",
                "link",
                "listing",
                "marquee",
                "menu",
                "meta",
                "nav",
                "noembed",
                "noframes",
                "noscript",
                "object",
                "ol",
                "p",
                "param",
                "plaintext",
                "pre",
                "script",
                "section",
                "select",
                "style",
                "summary",
                "table",
                "tbody",
                "td",
                "textarea",
                "tfoot",
                "th",
                "thead",
                "title",
                "tr",
                "ul",
                "wbr",
                "xmp",
            )
        val TagMathMlTextIntegration = arrayOf<String>("mi", "mn", "mo", "ms", "mtext")
        val TagSvgHtmlIntegration = arrayOf("desc", "foreignObject", "title")
        const val MaxScopeSearchDepth =
            100 // prevents the parser bogging down in exceptionally broken pages
        private const val maxQueueDepth =
            256 // an arbitrary tension point between real HTML and crafted pain

        private fun onStack(
            queue: List<Element?>,
            element: Element,
        ): Boolean {
            val bottom: Int = queue.size - 1
            val upper = if (bottom >= maxQueueDepth) bottom - maxQueueDepth else 0
            for (pos in bottom downTo upper) {
                val next: Element? = queue[pos]
                if (next === element) {
                    return true
                }
            }
            return false
        }

        private const val maxUsedFormattingElements = 12 // limit how many elements get recreated
    }
}
