package dog.del.app.frontend

import dog.del.app.api.apiCredentials
import dog.del.app.dto.CreateDocumentDto
import dog.del.app.dto.CreateDocumentResponseDto
import dog.del.app.session.user
import dog.del.app.stats.StatisticsReporter
import dog.del.app.stats.StatisticsReporter.Event
import dog.del.app.utils.createKey
import dog.del.app.utils.slug
import dog.del.commons.isUrl
import dog.del.commons.keygen.KeyGenerator
import dog.del.data.base.DB
import dog.del.data.base.Database
import dog.del.data.base.model.document.XdDocument
import dog.del.data.base.model.document.XdDocumentType
import dog.del.data.base.suspended
import io.ktor.application.ApplicationCall
import io.ktor.application.call
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.request.contentType
import io.ktor.request.receive
import io.ktor.request.receiveParameters
import io.ktor.request.receiveText
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.Route
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.route
import io.ktor.util.getOrFail
import jetbrains.exodus.database.TransientEntityStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.ktor.ext.inject

fun Route.legacyApi() = route("/") {
    val db by inject<TransientEntityStore>()
    val slugGen by inject<KeyGenerator>()
    val reporter by inject<StatisticsReporter>()

    get("raw/{slug}") {
        var docContent: String? = null
        val doc = db.suspended(readonly = true) {
            XdDocument.find(call.slug)?.also {
                docContent = it.stringContent
            }
        }
        if (doc == null) {
            call.respond(HttpStatusCode.NotFound, "No Document found")
        } else {
            call.respondText(docContent!!)
            GlobalScope.launch {
                reporter.reportImpression(call.slug, false, call.request)
            }
        }
    }

    post("documents") {
        when (call.request.contentType().withoutParameters()) {
            ContentType.Application.Json -> {
                val dto = call.receive<CreateDocumentDto>()
                call.createDocument(dto, db, slugGen, reporter)
            }
            ContentType.MultiPart.FormData -> {
                val params = call.receiveParameters()
                val dto = CreateDocumentDto(
                    content = params.getOrFail("data"),
                    slug = params["slug"]
                )
                call.createDocument(dto, db, slugGen, reporter)
            }
            else -> {
                println(call.request.contentType())
                val dto = CreateDocumentDto(
                    content = call.receiveText()
                )
                call.createDocument(dto, db, slugGen, reporter)
            }
        }
    }
}

suspend fun ApplicationCall.createDocument(
    dto: CreateDocumentDto,
    db: TransientEntityStore,
    slugGen: KeyGenerator,
    reporter: StatisticsReporter
) = withContext(Dispatchers.DB) {
    // TODO: uuh yea this ain't too beautiful
    val slugError = if (!dto.slug.isNullOrBlank()) XdDocument.verifySlug(dto.slug) else null
    val result = if (dto.content.isBlank()) {
        HttpStatusCode.BadRequest to CreateDocumentResponseDto(
            message = "Paste content cannot be empty"
        )
    } else if (slugError != null) {
        HttpStatusCode.BadRequest to CreateDocumentResponseDto(
            message = slugError
        )
    } else {
        val isFrontend = parameters["frontend"]?.toBoolean() ?: false
        val apiCreds = if (isFrontend) null else apiCredentials(db)
        if (apiCreds != null) {
            GlobalScope.launch {
                reporter.reportEvent(Event.API_KEY_USE, request)
            }
        }
        val fUser = user(db, !isFrontend)
        withContext(Database.dispatcher) {
            db.transactional {
                val usr = apiCreds?.user ?: fUser
                val canCreate = apiCreds?.canCreateDocuments ?: true
                // If the user is able to edit documents in general
                val canEdit = apiCreds?.canUpdateDocuments ?: true
                if (dto.slug.isNullOrBlank()) {
                    if (!canCreate) {
                        HttpStatusCode.Forbidden to CreateDocumentResponseDto(
                            message = "You do not have the permission to create documents"
                        )
                    } else {
                        val isUrl = dto.content.isUrl()
                        val slug = slugGen.createKey(db, isUrl)
                        val doc = XdDocument.new {
                            this.slug = slug
                            owner = usr
                            stringContent = dto.content.trim()
                            type = if (isUrl) XdDocumentType.URL else XdDocumentType.PASTE
                        }

                        GlobalScope.launch {
                            val event = if (isUrl) Event.URL_CREATE else Event.PASTE_CREATE
                            reporter.reportEvent(event, request)
                        }
                        HttpStatusCode.OK to CreateDocumentResponseDto(
                            isUrl = isUrl,
                            key = doc.slug
                        )
                    }
                } else {
                    // TODO: Properly validate slug and handle failures
                    val doc = XdDocument.find(dto.slug)
                    if (doc != null) {
                        if (!canEdit) {
                            HttpStatusCode.Forbidden to CreateDocumentResponseDto(
                                message = "You do not have the permission to edit documents"
                            )
                        } else if (doc.userCanEdit(usr)) {
                            val isUrl = dto.content.isUrl()
                            doc.stringContent = dto.content.trim()
                            doc.type = if (isUrl) XdDocumentType.URL else XdDocumentType.PASTE
                            val version = doc.version++
                            val id = doc.xdId
                            GlobalScope.launch {
                                reporter.reportEvent(Event.DOC_EDIT, request)
                            }

                            HttpStatusCode.OK to CreateDocumentResponseDto(
                                isUrl = isUrl,
                                key = doc.slug
                            )
                        } else {
                            HttpStatusCode.Conflict to CreateDocumentResponseDto(
                                message = "This URL is already in use, please choose a different one"
                            )
                        }
                    } else {
                        if (!canCreate) {
                            HttpStatusCode.Forbidden to CreateDocumentResponseDto(
                                message = "You do not have the permission to create documents"
                            )
                        } else {
                            val isUrl = dto.content.isUrl()
                            val doc = XdDocument.new {
                                slug = dto.slug
                                owner = usr
                                stringContent = dto.content.trim()
                                type = if (isUrl) XdDocumentType.URL else XdDocumentType.PASTE
                            }

                            GlobalScope.launch {
                                val event = if (isUrl) Event.URL_CREATE else Event.PASTE_CREATE
                                reporter.reportEvent(event, request)
                            }
                            HttpStatusCode.OK to CreateDocumentResponseDto(
                                isUrl = isUrl,
                                key = doc.slug
                            )
                        }
                    }
                }
            }
        }
    }
    respond(result.first, result.second)
}
