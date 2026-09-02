package com.qualityverifier.server.admin

import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.br
import kotlinx.html.button
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.input
import kotlinx.html.main
import kotlinx.html.strong
import kotlinx.html.hiddenInput
import kotlinx.html.img
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.style
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.textInput
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.ul
import kotlinx.html.unsafe
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The portal's markup.
 *
 * kotlinx.html rather than a template string, and the reason is escaping: every page here
 * renders text a customer typed or a name they chose, and `+"..."` escapes by default
 * whereas a string template does not. Getting that wrong on a page that also holds a CSRF
 * token would be a stored-XSS-to-account-takeover chain.
 *
 * No JavaScript and no build step. Forms post, pages render. On a 3.7 GB box shared with
 * Postgres, and for three people, anything more is cost without benefit.
 */
private val TIMESTAMP: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy HH:mm").withZone(ZoneId.of("Africa/Kampala"))

/** East African time throughout: it is when the assessments actually happened. */
fun Instant.readable(): String = TIMESTAMP.format(this)

private const val CSS = """
  :root { --ink:#241a12; --paper:#faf6f1; --line:#e2d6c8; --accent:#7b4b2a; --muted:#6b5b4d; }
  * { box-sizing:border-box; }
  body { margin:0; font:16px/1.5 system-ui,-apple-system,Segoe UI,sans-serif;
         color:var(--ink); background:var(--paper); }
  header { background:#fff; border-bottom:1px solid var(--line); padding:0 24px; }
  header .row { display:flex; align-items:center; gap:24px; max-width:1100px; margin:0 auto;
                min-height:56px; flex-wrap:wrap; }
  header strong { letter-spacing:.08em; }
  nav a { color:var(--muted); text-decoration:none; padding:8px 0; margin-right:18px;
          display:inline-block; }
  nav a:hover, nav a.on { color:var(--accent); border-bottom:2px solid var(--accent); }
  main { max-width:1100px; margin:0 auto; padding:24px; }
  h1 { font-size:22px; margin:0 0 4px; } h2 { font-size:17px; margin:28px 0 8px; }
  .sub { color:var(--muted); margin:0 0 20px; }
  table { width:100%; border-collapse:collapse; background:#fff; border:1px solid var(--line);
          border-radius:8px; overflow:hidden; }
  th { text-align:left; font-size:12px; text-transform:uppercase; letter-spacing:.06em;
       color:var(--muted); padding:10px 12px; border-bottom:1px solid var(--line); }
  td { padding:10px 12px; border-bottom:1px solid #f0e8de; vertical-align:top; }
  tr:last-child td { border-bottom:none; }
  form.inline { display:inline; }
  input[type=text], input[type=password], input[type=email] {
      padding:9px 10px; border:1px solid var(--line); border-radius:6px; font:inherit;
      background:#fff; min-width:220px; }
  button { padding:9px 14px; border:none; border-radius:6px; background:var(--accent);
           color:#fff; font:inherit; cursor:pointer; }
  button.quiet { background:#efe6da; color:var(--ink); }
  .card { background:#fff; border:1px solid var(--line); border-radius:8px; padding:20px;
          max-width:420px; }
  .turn { background:#fff; border:1px solid var(--line); border-radius:8px; padding:14px 16px;
          margin-bottom:12px; }
  .turn.user { background:#f4ece2; }
  .who { font-size:12px; text-transform:uppercase; letter-spacing:.06em; color:var(--muted);
         margin-bottom:6px; }
  .text { white-space:pre-wrap; }
  .shots { display:flex; flex-wrap:wrap; gap:8px; margin-top:10px; }
  .shots img { width:180px; height:180px; object-fit:cover; border-radius:6px;
               border:1px solid var(--line); }
  .warn { background:#fdf1e4; border:1px solid #e7c9a5; border-radius:6px; padding:10px 12px;
          margin-bottom:16px; }
  .err { background:#fdeaea; border:1px solid #e7b0b0; border-radius:6px; padding:10px 12px;
         margin-bottom:16px; }
  .muted { color:var(--muted); } .mono { font-family:ui-monospace,Menlo,monospace; font-size:13px; }
  /* Padding is part of the code: the quiet zone is white and needs somewhere to sit. */
  .qr { margin:16px 0; padding:8px; background:#fff; display:inline-block; border-radius:4px; }
  .check { display:block; margin:12px 0; font-weight:400; }
  .pager { margin-top:16px; display:flex; gap:12px; }
"""

fun HTML.page(heading: String, session: AdminSession?, current: String = "", block: FlowContent.() -> Unit) {
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        // Nothing on these pages is for anyone else, and a portal that shows customer
        // photographs should not be sitting in a shared proxy cache.
        meta(name = "robots", content = "noindex, nofollow")
        title { +"$heading — Kagua admin" }
        style { unsafe { +CSS } }
        link(rel = "icon", href = "data:,")
    }
    body {
        if (session != null) {
            header {
                div("row") {
                    strongText("KAGUA ADMIN")
                    nav {
                        navLink("/admin", "Overview", current == "home")
                        navLink("/admin/assessments", "Assessments", current == "assessments")
                        navLink("/admin/users", "Users", current == "users")
                        navLink("/admin/invites", "Invites", current == "invites")
                        navLink("/admin/admins", "Admins", current == "admins")
                        navLink("/admin/audit", "Audit", current == "audit")
                    }
                    div {
                        span("muted") { +session.email }
                        +" "
                        form(action = "/admin/logout", method = kotlinx.html.FormMethod.post, classes = "inline") {
                            hiddenInput(name = "csrf") { value = session.csrfToken }
                            button(type = ButtonType.submit, classes = "quiet") { +"Sign out" }
                        }
                    }
                }
            }
        }
        main {
            h1 { +heading }
            block()
        }
    }
}

private fun FlowContent.strongText(text: String) = strong { +text }

private fun FlowContent.navLink(href: String, text: String, on: Boolean) {
    a(href = href, classes = if (on) "on" else null) { +text }
}

fun FlowContent.subtitle(text: String) = p("sub") { +text }

fun FlowContent.warning(text: String) = div("warn") { +text }

fun FlowContent.errorBox(text: String) = div("err") { +text }

/** A form that carries the CSRF token. Every mutating form on the portal uses this. */
fun FlowContent.postForm(
    action: String,
    session: AdminSession,
    inline: Boolean = false,
    block: FlowContent.() -> Unit,
) {
    form(action = action, method = kotlinx.html.FormMethod.post, classes = if (inline) "inline" else null) {
        hiddenInput(name = "csrf") { value = session.csrfToken }
        block()
    }
}

fun FlowContent.labelledField(
    caption: String,
    field: String,
    type: InputType = InputType.text,
    initial: String = "",
) {
    p {
        label { +caption }
        br()
        input(type = type, name = field) { value = initial }
    }
}

/** Prev/next links. Offset paging: fine for tables nobody scrolls to the end of. */
fun FlowContent.pager(base: String, offset: Int, limit: Int, hasMore: Boolean) {
    div("pager") {
        if (offset > 0) {
            a(href = "$base?offset=${(offset - limit).coerceAtLeast(0)}") { +"← Newer" }
        }
        if (hasMore) a(href = "$base?offset=${offset + limit}") { +"Older →" }
    }
}

// --------------------------------------------------------------------- pages

fun HTML.loginPage(error: String?) = page("Sign in", null) {
    div("card") {
        if (error != null) errorBox(error)
        form(action = "/admin/login", method = kotlinx.html.FormMethod.post) {
            labelledField("Email", "email", InputType.email)
            labelledField("Password", "password", InputType.password)
            button(type = ButtonType.submit) { +"Continue" }
        }
    }
}

fun HTML.twoFactorPage(error: String?, enrolment: Enrolment?) =
    page(if (enrolment != null) "Set up your authenticator" else "Two-factor code", null) {
        div("card") {
            if (error != null) errorBox(error)
            if (enrolment != null) {
                p {
                    +"Scan this with an authenticator app, then enter the code it shows. "
                    +"You will not be able to see it again."
                }
                qrCode(enrolment.uri)
                p("muted") {
                    +"Cannot scan? Type this into the app instead:"
                    br()
                    span("mono") { +enrolment.secret }
                }
            }
            form(action = "/admin/2fa", method = kotlinx.html.FormMethod.post) {
                labelledField("Six-digit code", "code")
                label("check") {
                    input(type = InputType.checkBox, name = "remember")
                    +" Remember this browser for 30 days"
                }
                p("muted") {
                    +"Only on a computer you control. It skips the code, not the password — "
                    +"and it can be undone from the Admins page."
                }
                button(type = ButtonType.submit) { +"Sign in" }
            }
        }
    }

data class Enrolment(val secret: String, val uri: String)

/**
 * The enrolment QR, inline.
 *
 * `unsafe` is doing something genuinely unsafe, so the path data is checked against a
 * strict pattern before it is emitted. [Qr] builds it from integers and the characters
 * `Mhvz,-` and nothing else, so the check should never fire — which is the point: if a
 * future change ever routes user input through here, this fails closed and falls back to
 * the typed secret rather than injecting markup into a page that renders customer text.
 */
private fun FlowContent.qrCode(uri: String) {
    val qr = runCatching { Qr.encode(uri) }.getOrNull()
    if (qr == null || !SAFE_PATH.matches(qr.pathData)) {
        // No QR is a worse experience, not a broken one: the secret is shown below either
        // way, so enrolment still completes by typing.
        p("muted") { +"(Could not draw the QR code — use the secret below.)" }
        return
    }
    div("qr") {
        unsafe {
            +"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${qr.modules} ${qr.modules}" """
            +"""width="220" height="220" shape-rendering="crispEdges" role="img" """
            +"""aria-label="Authenticator enrolment QR code">"""
            // White behind the code, always. A dark-mode browser inverting the page would
            // otherwise leave a code no scanner can read.
            +"""<rect width="${qr.modules}" height="${qr.modules}" fill="#fff"/>"""
            +"""<path d="${qr.pathData}" fill="#000"/></svg>"""
        }
    }
}

private val SAFE_PATH = Regex("[Mhvz0-9,\\-]*")


fun HTML.overviewPage(session: AdminSession, counts: Overview) = page("Overview", session, "home") {
    subtitle("What is in the system right now.")
    table {
        tbody {
            countRow("Accounts", counts.users)
            countRow("Assessments", counts.sessions)
            countRow("Assessments today", counts.sessionsToday)
            countRow("Photos stored", counts.photos)
            countRow("Invite codes unused", counts.unusedInvites)
        }
    }
    h2 { +"Recently" }
    if (counts.recentAudit.isEmpty()) {
        p("muted") { +"Nothing yet." }
    } else {
        table {
            thead { tr { th { +"When" }; th { +"Who" }; th { +"Did" }; th { +"To" } } }
            tbody {
                counts.recentAudit.forEach { entry ->
                    tr {
                        td { +entry.createdAt.readable() }
                        td { +entry.adminEmail }
                        td { +entry.action }
                        td("mono") { +(entry.target ?: "") }
                    }
                }
            }
        }
    }
}

data class Overview(
    val users: Int,
    val sessions: Int,
    val sessionsToday: Int,
    val photos: Int,
    val unusedInvites: Int,
    val recentAudit: List<AuditRow>,
)

private fun kotlinx.html.TBODY.countRow(label: String, value: Int) {
    tr { td { +label }; td { strongText(value.toString()) } }
}

fun HTML.usersPage(session: AdminSession, page: Page<UserRow>, offset: Int, limit: Int, search: String?) =
    page("Users", session, "users") {
        subtitle("Everyone with an account, newest first.")
        form(action = "/admin/users", method = kotlinx.html.FormMethod.get) {
            textInput(name = "q") { value = search ?: ""; placeholder = "phone, name or business" }
            +" "
            button(type = ButtonType.submit, classes = "quiet") { +"Search" }
        }
        br()
        table {
            thead {
                tr {
                    th { +"Phone" }; th { +"Name" }; th { +"Type" }; th { +"Business" }
                    th { +"Assessments" }; th { +"Joined" }
                }
            }
            tbody {
                page.items.forEach { user ->
                    tr {
                        td {
                            +user.phone
                            if (user.deleted) span("muted") { +" (deleted)" }
                        }
                        td { +(user.name ?: "—") }
                        td { +(user.accountType ?: "—") }
                        td { +(user.businessName ?: "—") }
                        td {
                            if (user.assessments > 0) {
                                a(href = "/admin/assessments?user=${user.id}") { +user.assessments.toString() }
                            } else +"0"
                        }
                        td { +user.createdAt.readable() }
                    }
                }
            }
        }
        pager("/admin/users", offset, limit, page.hasMore)
    }

fun HTML.assessmentsPage(
    session: AdminSession,
    page: Page<AdminSessionRow>,
    offset: Int,
    limit: Int,
) = page("Assessments", session, "assessments") {
    subtitle("Every assessment, newest first. Open one to read the conversation.")
    table {
        thead {
            tr {
                th { +"When" }; th { +"Item" }; th { +"Account" }; th { +"Turns" }
                th { +"Photos" }; th { +"Verdict" }
            }
        }
        tbody {
            page.items.forEach { row ->
                tr {
                    td { a(href = "/admin/assessments/${row.id}") { +row.createdAt.readable() } }
                    td { +row.itemTypeId }
                    td {
                        +(row.userPhone ?: "—")
                        if (row.userName != null) span("muted") { +" ${row.userName}" }
                    }
                    td { +row.messageCount.toString() }
                    td { +row.photoCount.toString() }
                    td {
                        +(row.verdictLevelId ?: "—")
                        if (row.clientDeleted) span("muted") { +" · deleted by user" }
                    }
                }
            }
        }
    }
    pager("/admin/assessments", offset, limit, page.hasMore)
}

fun HTML.conversationPage(
    session: AdminSession,
    header: AdminSessionRow,
    turns: List<AdminMessageRow>,
) = page("Assessment", session, "assessments") {
    subtitle(
        "${header.itemTypeId} · ${header.userPhone ?: "unknown account"} · " +
            header.createdAt.readable(),
    )
    if (header.clientDeleted) {
        warning(
            "The customer deleted this assessment. It is kept for seven days from that " +
                "point and then removed for good.",
        )
    }
    turns.forEach { turn ->
        div(if (turn.role == "USER") "turn user" else "turn") {
            div("who") { +"${turn.role.lowercase()} · ${turn.createdAt.readable()}" }
            div("text") { +turn.text }
            if (turn.photoHashes.isNotEmpty()) {
                div("shots") {
                    turn.photoHashes.forEach { sha ->
                        // Shown, not hidden behind a click. Judging whether an assessment
                        // was accurate means looking at what the assistant was looking at,
                        // and these are workshop photographs read by academics in offices.
                        a(href = "/admin/photos/$sha") {
                            img(src = "/admin/photos/$sha", alt = "Photo from this turn") {
                                attributes["loading"] = "lazy"
                            }
                        }
                    }
                }
            }
        }
    }
    h2 { +"Export" }
    p("muted") { +"Photos are left out unless you ask for them." }
    ul {
        li { a(href = "/admin/export/assessment/${header.id}") { +"This conversation as JSON" } }
        li {
            a(href = "/admin/export/assessment/${header.id}?photos=true") {
                +"This conversation as JSON, photos included"
            }
        }
    }
}

fun HTML.invitesPage(session: AdminSession, invites: List<InviteRow>, notice: String?) =
    page("Invite codes", session, "invites") {
        subtitle("A code is needed to create an account. Sign-in does not use one.")
        if (notice != null) warning(notice)
        div("card") {
            postForm("/admin/invites", session) {
                labelledField("Who is it for", "label")
                button(type = ButtonType.submit) { +"Create a code" }
            }
        }
        br()
        table {
            thead {
                tr { th { +"Code" }; th { +"For" }; th { +"Used" }; th { +"Created" }; th { } }
            }
            tbody {
                invites.forEach { invite ->
                    tr {
                        td("mono") { +invite.code }
                        td { +(invite.label ?: "—") }
                        td { +invite.timesUsed.toString() }
                        td { +invite.createdAt.readable() }
                        td {
                            if (invite.revokedAt != null) {
                                span("muted") { +"revoked ${invite.revokedAt.readable()}" }
                            } else {
                                postForm("/admin/invites/${invite.code}/revoke", session, inline = true) {
                                    button(type = ButtonType.submit, classes = "quiet") { +"Revoke" }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

fun HTML.adminsPage(
    session: AdminSession,
    admins: List<AdminRow>,
    notice: String?,
    createdSecret: Enrolment?,
    devices: List<TrustedDevice>,
) = page("Admins", session, "admins") {
    subtitle("People who can read this portal.")
    if (notice != null) warning(notice)
    if (createdSecret != null) {
        div("warn") {
            p {
                strongText("Give this to them in person or over something private. ")
                +"It is shown once and cannot be recovered — if it is lost, reset their "
                +"2FA below and hand over the new one."
            }
            qrCode(createdSecret.uri)
            p("mono") { code { +createdSecret.secret } }
        }
    }
    div("card") {
        postForm("/admin/admins", session) {
            labelledField("Name", "name")
            labelledField("Email", "email", InputType.email)
            labelledField("Temporary password", "password", InputType.password)
            button(type = ButtonType.submit) { +"Add an admin" }
        }
    }
    br()
    table {
        thead {
            tr {
                th { +"Email" }; th { +"Name" }; th { +"2FA" }; th { +"Added by" }
                th { +"Last signed in" }; th { }
            }
        }
        tbody {
            admins.forEach { admin ->
                tr {
                    td {
                        +admin.email
                        if (admin.disabled) span("muted") { +" (disabled)" }
                    }
                    td { +admin.name }
                    td { +if (admin.twoFactorReady) "set up" else "not yet" }
                    td { +(admin.createdByEmail ?: "—") }
                    td { +(admin.lastSignInAt?.readable() ?: "never") }
                    td {
                        if (admin.id != session.adminId) {
                            val action = if (admin.disabled) "enable" else "disable"
                            postForm("/admin/admins/${admin.id}/$action", session, inline = true) {
                                button(type = ButtonType.submit, classes = "quiet") { +action }
                            }
                            if (!admin.disabled && admin.twoFactorReady) {
                                // Password-confirmed, because a reset is how somebody with
                                // a borrowed session would move a second factor onto their
                                // own device.
                                postForm(
                                    "/admin/admins/${admin.id}/reset-2fa",
                                    session,
                                    inline = true,
                                ) {
                                    input(type = InputType.password, name = "password") {
                                        attributes["placeholder"] = "your password"
                                    }
                                    button(type = ButtonType.submit, classes = "quiet") {
                                        +"Reset 2FA"
                                    }
                                }
                            }
                        } else span("muted") { +"you" }
                    }
                }
            }
        }
    }
    h2 { +"Remembered browsers" }
    div("card") {
        if (devices.isEmpty()) {
            p("muted") { +"None. You will be asked for a code every time you sign in." }
        } else {
            p {
                +"These browsers skip the code until they expire. Revoke them if one is on a "
                +"computer you no longer have."
            }
            table {
                thead { tr { th { +"Browser" }; th { +"Remembered" }; th { +"Last used" }; th { +"Expires" } } }
                tbody {
                    devices.forEach { device ->
                        tr {
                            td { +(device.label ?: "unknown") }
                            td { +device.createdAt.readable() }
                            td { +(device.lastUsedAt?.readable() ?: "not since") }
                            td { +device.expiresAt.readable() }
                        }
                    }
                }
            }
            postForm("/admin/devices/revoke", session) {
                button(type = ButtonType.submit, classes = "quiet") { +"Forget all of them" }
            }
        }
    }
    h2 { +"Your password" }
    div("card") {
        postForm("/admin/password", session) {
            labelledField("Current password", "current", InputType.password)
            labelledField("New password", "next", InputType.password)
            button(type = ButtonType.submit) { +"Change it" }
        }
    }
}

fun HTML.auditPage(session: AdminSession, page: Page<AuditRow>, offset: Int, limit: Int) =
    page("Audit", session, "audit") {
        subtitle("Who looked at what. This portal can read every conversation, so it keeps a record.")
        table {
            thead {
                tr { th { +"When" }; th { +"Who" }; th { +"Action" }; th { +"Target" }; th { +"Detail" }; th { +"From" } }
            }
            tbody {
                page.items.forEach { entry ->
                    tr {
                        td { +entry.createdAt.readable() }
                        td { +entry.adminEmail }
                        td { +entry.action }
                        td("mono") { +(entry.target ?: "") }
                        td { +(entry.detail ?: "") }
                        td("muted") { +(entry.ip ?: "") }
                    }
                }
            }
        }
        pager("/admin/audit", offset, limit, page.hasMore)
    }
