package com.polina.memify

import com.polina.memify.db.Templates
import com.polina.memify.storage.BUCKET_NAME
import com.polina.memify.storage.PUBLIC_BUCKET_URL
import com.polina.memify.storage.s3Client
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer

// Источник шаблонов — Imgflip Meme Generator API (https://imgflip.com/api), эндпоинт
// GET /get_memes. Бесплатный, без авторизации, отдаёт шаблоны в порядке от самых
// "заподписанных" за последние 30 дней к менее популярным — то есть уже актуальный,
// живой топ, а не статичный список.
//
// Раньше строки в Templates появлялись вручную/из старой Firestore-выгрузки. Теперь эта
// таблица регулярно синхронизируется с Imgflip (см. configureTemplatesSync ниже), а весь
// остальной код — TemplatesRoutes.kt (сортировка best/new, избранное, toggle-like) и схема
// в Tables.kt — не менялся: он как читал/писал Templates/TemplateFavourites, так и читает/
// пишет, просто теперь данные в Templates берутся не вручную, а из Imgflip.

private const val IMGFLIP_GET_MEMES_URL = "https://api.imgflip.com/get_memes"
private const val TEMPLATES_SYNC_INTERVAL_MS = 15 * 60 * 1000L // раз в 15 минут
private const val TEMPLATE_OBJECT_PREFIX = "templates/imgflip"
private val SUPPORTED_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

@Serializable
private data class ImgflipResponse(
    val success: Boolean,
    val data: ImgflipData? = null,
)

@Serializable
private data class ImgflipData(
    val memes: List<ImgflipMeme> = emptyList(),
)

@Serializable
private data class ImgflipMeme(
    val id: String,
    val name: String,
    val url: String,
    val width: Int,
    val height: Int,
)

private data class DownloadedTemplateImage(
    val bytes: ByteArray,
    val contentType: String,
)

private val imgflipHttpClient =
    HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

private suspend fun fetchImgflipTemplates(): List<ImgflipMeme> {
    val response: ImgflipResponse = imgflipHttpClient.get(IMGFLIP_GET_MEMES_URL).body()
    if (!response.success) return emptyList()
    return response.data?.memes.orEmpty()
}

private fun templateObjectKey(imgflipId: String, sourceUrl: String): String {
    val extension =
        runCatching { URI.create(sourceUrl).path.substringAfterLast('.', "jpg").lowercase() }
            .getOrDefault("jpg")
            .takeIf { it in SUPPORTED_IMAGE_EXTENSIONS }
            ?: "jpg"
    return "$TEMPLATE_OBJECT_PREFIX/$imgflipId.$extension"
}

private fun String.isStoredTemplateUrl(): Boolean =
    startsWith("$PUBLIC_BUCKET_URL/$TEMPLATE_OBJECT_PREFIX/")

private fun canonicalTemplateUrl(meme: ImgflipMeme): String {
    val extension = templateObjectKey(meme.id, meme.url).substringAfterLast('.')
    val slug =
        Normalizer.normalize(meme.name, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .replace("'", "")
            .replace("`", "")
            .replace(Regex("[^A-Za-z0-9]+"), "-")
            .trim('-')
    return "https://imgflip.com/s/meme/$slug.$extension"
}

private fun proxiedTemplateUrl(sourceUrl: String): String {
    val sourceWithoutScheme = sourceUrl.removePrefix("https://")
    val encodedSource = URLEncoder.encode(sourceWithoutScheme, StandardCharsets.UTF_8)
    return "https://images.weserv.nl/?url=$encodedSource"
}

private suspend fun downloadTemplateImage(meme: ImgflipMeme): DownloadedTemplateImage? {
    val candidates = listOf(canonicalTemplateUrl(meme), proxiedTemplateUrl(meme.url), meme.url)
    for (url in candidates) {
        val image =
            runCatching {
                val response = imgflipHttpClient.get(url)
                val bytes: ByteArray = response.body()
                if (response.status.value !in 200..299) error("${response.status}")
                if (bytes.isEmpty()) error("пустой файл")
                DownloadedTemplateImage(
                    bytes = bytes,
                    contentType = response.headers[HttpHeaders.ContentType] ?: "application/octet-stream",
                )
            }.getOrNull()
        if (image != null) return image
    }
    return null
}

private suspend fun mirrorTemplateImage(meme: ImgflipMeme): String? =
    try {
        val image = downloadTemplateImage(meme) ?: error("ни один источник не вернул изображение")

        val objectKey = templateObjectKey(meme.id, meme.url)
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(BUCKET_NAME)
                .key(objectKey)
                .contentType(image.contentType)
                .cacheControl("public, max-age=31536000, immutable")
                .build(),
            RequestBody.fromBytes(image.bytes),
        )
        "$PUBLIC_BUCKET_URL/$objectKey"
    } catch (e: Exception) {
        println("[templates-sync] Не удалось перенести ${meme.url} в Object Storage: ${e.message}")
        null
    }

/**
 * Переключает уже загруженные Imgflip-шаблоны на детерминированные Object Storage URL.
 * Перед первым деплоем этой версии объекты должны быть загружены скриптом
 * deploy/mirror-template-images.py.
 */
private fun migrateExistingTemplateUrls(): Int {
    val externalTemplates =
        transaction {
            Templates.selectAll()
                .map { row -> row[Templates.id] to row[Templates.url] }
                .filter { (id, url) -> id.startsWith("imgflip-") && !url.isStoredTemplateUrl() }
        }

    val templatesToMigrate =
        externalTemplates.mapNotNull { (templateId, sourceUrl) ->
            val imgflipId = templateId.removePrefix("imgflip-")
            val objectKey = templateObjectKey(imgflipId, sourceUrl)
            val objectExists =
                runCatching {
                    s3Client.headObject(
                        HeadObjectRequest.builder()
                            .bucket(BUCKET_NAME)
                            .key(objectKey)
                            .build(),
                    ).contentLength()?.let { it > 0 } == true
                }.getOrDefault(false)
            if (objectExists) templateId to "$PUBLIC_BUCKET_URL/$objectKey" else null
        }

    transaction {
        templatesToMigrate.forEach { (templateId, objectUrl) ->
            Templates.update({ Templates.id eq templateId }) {
                it[url] = objectUrl
            }
        }
    }
    return templatesToMigrate.size
}

/**
 * Затягивает актуальный список шаблонов с Imgflip и обновляет ими таблицу Templates
 * (insert для новых id, update для уже известных). Ничего не удаляет — если в таблице
 * были другие шаблоны, они останутся (в т.ч. чтобы не сломать ссылки из
 * TemplateFavourites/Posts на template_id).
 *
 * @return сколько шаблонов вернул Imgflip
 */
suspend fun syncTemplatesFromImgflip(): Int {
    val memes = fetchImgflipTemplates()
    if (memes.isEmpty()) return 0

    val ids = memes.map { "imgflip-${it.id}" }
    val total = memes.size

    val existingUrls =
        transaction {
            Templates.selectAll()
                .where { Templates.id inList ids }
                .associate { row -> row[Templates.id] to row[Templates.url] }
        }

    val memesWithStoredUrls =
        memes.mapIndexedNotNull { index, meme ->
            val templateId = "imgflip-${meme.id}"
            val existingUrl = existingUrls[templateId]
            val storedUrl = existingUrl?.takeIf { it.isStoredTemplateUrl() } ?: mirrorTemplateImage(meme)
            if (storedUrl == null) {
                println("[templates-sync] Пропускаю ${meme.id}: копии в Object Storage пока нет")
                null
            } else {
                Triple(index, meme, storedUrl)
            }
        }

    transaction {
        val existingIds = existingUrls.keys

        memesWithStoredUrls.forEach { (index, meme, storedUrl) ->
            val templateId = "imgflip-${meme.id}"
            // Imgflip уже отдаёт мемы от самого популярного к менее популярному —
            // переносим этот порядок в usedCount, чтобы ?sort=best (сортировка по
            // usedCount) отражала актуальный рейтинг Imgflip.
            val rank = total - index

            if (templateId in existingIds) {
                Templates.update({ Templates.id eq templateId }) {
                    it[name] = meme.name
                    it[url] = storedUrl
                    it[width] = meme.width
                    it[height] = meme.height
                    it[usedCount] = rank
                    // createdAt намеренно не трогаем, иначе при каждой синхронизации
                    // шаблон "молодел бы" и портил ?sort=new.
                }
            } else {
                Templates.insert {
                    it[id] = templateId
                    it[name] = meme.name
                    it[url] = storedUrl
                    it[width] = meme.width
                    it[height] = meme.height
                    it[usedCount] = rank
                }
            }
        }
    }

    return memes.size
}

fun Application.configureTemplatesSync() {
    launch {
        val migratedCount = migrateExistingTemplateUrls()
        if (migratedCount > 0) {
            println("[templates-sync] Переключено на Object Storage: $migratedCount шаблонов")
        }
        syncSafely()
        while (isActive) {
            delay(TEMPLATES_SYNC_INTERVAL_MS)
            syncSafely()
        }
    }
}

private suspend fun syncSafely() {
    try {
        val count = syncTemplatesFromImgflip()
        println("[templates-sync] Синхронизировано с Imgflip: $count шаблонов")
    } catch (e: Exception) {
        println("[templates-sync] Не удалось синхронизировать шаблоны с Imgflip: ${e.message}")
    }
}
