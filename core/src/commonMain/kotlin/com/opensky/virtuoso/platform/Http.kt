package com.opensky.virtuoso.platform

/** Respuesta HTTP en texto (UTF-8). */
class HttpResponse(val code: Int, val body: String)

/**
 * GET HTTP(S) que devuelve el cuerpo como texto. No lanza por códigos 4xx/5xx (los devuelve en
 * [HttpResponse.code]); sí por errores de red o de tiempo de espera ([IOException]).
 */
expect suspend fun httpGet(url: String, timeoutMs: Int, userAgent: String): HttpResponse
