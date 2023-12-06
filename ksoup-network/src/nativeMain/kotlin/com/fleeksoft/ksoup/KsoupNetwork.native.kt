package com.fleeksoft.ksoup

import com.fleeksoft.ksoup.network.NetworkHelper
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.parser.Parser
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.runBlocking

/**
 * Use to fetch and parse a HTML page.
 *
 * Use examples:
 *
 *  * `Document doc = Ksoup.parseGetRequest("http://example.com")`
 *
 * @param url URL to connect to. The protocol must be `http` or `https`.
 * @return sane HTML
 *
 */
public fun Ksoup.parseGetRequestBlocking(
    url: String,
    httpRequestBuilder: HttpRequestBuilder.() -> Unit = {},
    parser: Parser = Parser.htmlParser(),
): Document = runBlocking {
    val httpResponse = NetworkHelper.instance.get(url, httpRequestBuilder = httpRequestBuilder)
//        url can be changed after redirection
    val finalUrl = httpResponse.request.url.toString()
    val response = httpResponse.bodyAsText()
    return@runBlocking parse(html = response, parser = parser, baseUri = finalUrl)
}

/**
 * Use to fetch and parse a HTML page.
 *
 * Use examples:
 *
 *  * `Document doc = Ksoup.parseSubmitRequest("http://example.com", params = mapOf("param1Key" to "param1Value"))`
 *
 * @param url URL to connect to. The protocol must be `http` or `https`.
 * @return sane HTML
 *
 */
public fun Ksoup.parseSubmitRequestBlocking(
    url: String,
    params: Map<String, String> = emptyMap(),
    httpRequestBuilder: HttpRequestBuilder.() -> Unit = {},
    parser: Parser = Parser.htmlParser(),
): Document = runBlocking {
    val httpResponse = NetworkHelper.instance.submitForm(
        url = url,
        params = params,
        httpRequestBuilder = httpRequestBuilder,
    )
//            url can be changed after redirection
    val finalUrl = httpResponse.request.url.toString()
    val result: String = httpResponse.bodyAsText()
    return@runBlocking parse(html = result, parser = parser, baseUri = finalUrl)
}

/**
 * Use to fetch and parse a HTML page.
 *
 * Use examples:
 *
 *  * `Document doc = Ksoup.parsePostRequest("http://example.com")`
 *
 * @param url URL to connect to. The protocol must be `http` or `https`.
 * @return sane HTML
 *
 */
public fun Ksoup.parsePostRequestBlocking(
    url: String,
    httpRequestBuilder: HttpRequestBuilder.() -> Unit = {},
    parser: Parser = Parser.htmlParser(),
): Document = runBlocking {
    val httpResponse = NetworkHelper.instance.post(
        url = url,
        httpRequestBuilder = httpRequestBuilder,
    )
//            url can be changed after redirection
    val finalUrl = httpResponse.request.url.toString()
    val result: String = httpResponse.bodyAsText()
    return@runBlocking parse(html = result, parser = parser, baseUri = finalUrl)
}
