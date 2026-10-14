@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class
)

package com.local.monolith

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min

enum class HeadingStyle(val label: String) {
    TEXT("Text"),
    SMALL("Small heading"),
    LARGE("Large heading")
}

data class InlineStyleSpan(
    val start: Int,
    val end: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val link: String? = null
)

data class RichNoteDocument(
    val text: String = "",
    val spans: List<InlineStyleSpan> = emptyList(),
    val lineHeadings: Map<Int, HeadingStyle> = emptyMap()
) {
    fun toPlainPreview(): String {
        return text.lines()
            .map { it.removePrefix("• ").removePrefix("☐ ").removePrefix("☑ ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    fun serialize(): String {
        val spansArray = JSONArray()
        spans.filter { it.end > it.start && (it.bold || it.italic || !it.link.isNullOrBlank()) }
            .forEach { span ->
                spansArray.put(JSONObject().apply {
                    put("s", span.start)
                    put("e", span.end)
                    if (span.bold) put("b", true)
                    if (span.italic) put("i", true)
                    if (!span.link.isNullOrBlank()) put("l", span.link)
                })
            }
        val headingsObj = JSONObject()
        lineHeadings.forEach { (lineIdx, style) ->
            if (style != HeadingStyle.TEXT) headingsObj.put(lineIdx.toString(), style.name)
        }
        return JSONObject().apply {
            put("_format", "rich_v2")
            put("text", text)
            put("spans", spansArray)
            put("headings", headingsObj)
        }.toString()
    }

    companion object {
        fun deserialize(rawContent: String): RichNoteDocument {
            if (rawContent.isBlank()) return RichNoteDocument()
            if (rawContent.trimStart().startsWith("{")) {
                try {
                    val obj = JSONObject(rawContent)
                    if (obj.optString("_format") == "rich_v2") {
                        val text = obj.optString("text", "")
                        val spans = mutableListOf<InlineStyleSpan>()
                        val spansArray = obj.optJSONArray("spans")
                        if (spansArray != null) {
                            for (i in 0 until spansArray.length()) {
                                val sObj = spansArray.getJSONObject(i)
                                val start = sObj.optInt("s", 0).coerceIn(0, text.length)
                                val end = sObj.optInt("e", 0).coerceIn(0, text.length)
                                if (end > start) {
                                    spans.add(
                                        InlineStyleSpan(
                                            start = start,
                                            end = end,
                                            bold = sObj.optBoolean("b", false),
                                            italic = sObj.optBoolean("i", false),
                                            link = sObj.optString("l", "").ifBlank { null }
                                        )
                                    )
                                }
                            }
                        }
                        val headings = mutableMapOf<Int, HeadingStyle>()
                        val headingsObj = obj.optJSONObject("headings")
                        if (headingsObj != null) {
                            val keys = headingsObj.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                val lineIdx = key.toIntOrNull() ?: continue
                                val style = runCatching { HeadingStyle.valueOf(headingsObj.optString(key)) }
                                    .getOrDefault(HeadingStyle.TEXT)
                                if (style != HeadingStyle.TEXT) headings[lineIdx] = style
                            }
                        }
                        return RichNoteDocument(text = text, spans = spans, lineHeadings = headings)
                    }
                } catch (_: Exception) {}
            }
            return parseLegacyMarkdown(rawContent)
        }

        private fun parseLegacyMarkdown(raw: String): RichNoteDocument {
            val cleanLines = mutableListOf<String>()
            val headings = mutableMapOf<Int, HeadingStyle>()
            val spans = mutableListOf<InlineStyleSpan>()
            var currentOffset = 0

            raw.lines().forEachIndexed { lineIndex, rawLine ->
                var working = rawLine
                val trimmed = working.trimStart()
                when {
                    trimmed.startsWith("# ") -> {
                        headings[lineIndex] = HeadingStyle.LARGE
                        working = trimmed.removePrefix("# ")
                    }
                    trimmed.startsWith("## ") || trimmed.startsWith("### ") -> {
                        headings[lineIndex] = HeadingStyle.SMALL
                        working = trimmed.replaceFirst(Regex("^#{2,3}\\s+"), "")
                    }
                    trimmed.startsWith("- [ ] ") -> working = "☐ " + trimmed.removePrefix("- [ ] ")
                    trimmed.startsWith("- [x] ") || trimmed.startsWith("- [X] ") -> working = "☑ " + trimmed.substring(6)
                    trimmed.startsWith("- ") || trimmed.startsWith("* ") -> working = "• " + trimmed.substring(2)
                    trimmed.startsWith("> ") -> working = trimmed.removePrefix("> ")
                }
                working = working.replace(Regex("""\[\[([^\[\]]+)\]\]"""), "$1")

                val sb = StringBuilder()
                var idx = 0
                while (idx < working.length) {
                    if (working.startsWith("**", idx)) {
                        val closeIdx = working.indexOf("**", idx + 2)
                        if (closeIdx > idx + 2) {
                            val boldContent = working.substring(idx + 2, closeIdx)
                            val spanStart = currentOffset + sb.length
                            sb.append(boldContent)
                            val spanEnd = currentOffset + sb.length
                            spans.add(InlineStyleSpan(start = spanStart, end = spanEnd, bold = true))
                            idx = closeIdx + 2
                            continue
                        }
                    }
                    sb.append(working[idx])
                    idx++
                }
                val finalLine = sb.toString()
                cleanLines.add(finalLine)
                currentOffset += finalLine.length + 1
            }
            return RichNoteDocument(
                text = cleanLines.joinToString("\n"),
                spans = spans,
                lineHeadings = headings
            )
        }
    }
}

data class Note(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val document: RichNoteDocument,
    val isPinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val previewText: String get() = document.toPlainPreview()
}

class NotesDatabase(context: Context) : SQLiteOpenHelper(context, "monolith_vault.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS notes (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                folder TEXT NOT NULL,
                tags TEXT NOT NULL,
                color_index INTEGER NOT NULL,
                is_pinned INTEGER NOT NULL,
                is_locked INTEGER NOT NULL,
                is_trashed INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
        cleanupLegacyPromotionalNotes(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) cleanupLegacyPromotionalNotes(db)
    }

    private fun cleanupLegacyPromotionalNotes(db: SQLiteDatabase) {
        try {
            db.execSQL(
                """
                DELETE FROM notes 
                WHERE title IN ('Welcome to Monolith Vault', 'Zero-Cloud Architecture', 'Quick Capture Scratchpad')
                """.trimIndent()
            )
            db.execSQL("UPDATE notes SET is_locked = 0 WHERE is_locked != 0")
            db.execSQL("DELETE FROM notes WHERE is_trashed = 1")
        } catch (_: Exception) {}
    }

    fun upsertNote(note: Note) {
        try {
            val values = ContentValues().apply {
                put("id", note.id)
                put("title", note.title)
                put("content", note.document.serialize())
                put("folder", "Notes")
                put("tags", "")
                put("color_index", 0)
                put("is_pinned", if (note.isPinned) 1 else 0)
                put("is_locked", 0)
                put("is_trashed", 0)
                put("created_at", note.createdAt)
                put("updated_at", note.updatedAt)
            }
            writableDatabase.insertWithOnConflict("notes", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (_: Exception) {}
    }

    fun deleteNote(id: String) {
        try {
            writableDatabase.delete("notes", "id = ?", arrayOf(id))
        } catch (_: Exception) {}
    }

    fun getAllNotes(): List<Note> {
        val list = mutableListOf<Note>()
        try {
            val cursor = readableDatabase.query(
                "notes", null, "is_trashed = 0", null, null, null, "updated_at DESC"
            )
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(c.getColumnIndexOrThrow("id")) ?: UUID.randomUUID().toString()
                    val title = c.getString(c.getColumnIndexOrThrow("title")) ?: ""
                    val rawContent = c.getString(c.getColumnIndexOrThrow("content")) ?: ""
                    val isPinned = c.getInt(c.getColumnIndexOrThrow("is_pinned")) == 1
                    val createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
                    val updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
                    list.add(
                        Note(
                            id = id,
                            title = if (title == "Untitled Note") "" else title,
                            document = RichNoteDocument.deserialize(rawContent),
                            isPinned = isPinned,
                            createdAt = createdAt,
                            updatedAt = updatedAt
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return list.sortedByDescending { it.updatedAt }
    }
}

fun adjustDocumentOnEdit(
    oldDoc: RichNoteDocument,
    newText: String,
    activeBold: Boolean,
    activeItalic: Boolean
): RichNoteDocument {
    val oldText = oldDoc.text
    if (oldText == newText) return oldDoc

    var prefix = 0
    val minLen = min(oldText.length, newText.length)
    while (prefix < minLen && oldText[prefix] == newText[prefix]) prefix++

    var oldSuffix = oldText.length
    var newSuffix = newText.length
    while (oldSuffix > prefix && newSuffix > prefix && oldText[oldSuffix - 1] == newText[newSuffix - 1]) {
        oldSuffix--
        newSuffix--
    }

    val delta = newText.length - oldText.length
    val insertedCount = newSuffix - prefix

    val updatedSpans = mutableListOf<InlineStyleSpan>()
    for (span in oldDoc.spans) {
        val newStart = when {
            span.start <= prefix -> span.start
            span.start >= oldSuffix -> (span.start + delta).coerceAtLeast(prefix)
            else -> prefix
        }
        val newEnd = when {
            span.end <= prefix -> span.end
            span.end >= oldSuffix -> (span.end + delta).coerceAtLeast(prefix)
            else -> prefix
        }
        if (newEnd > newStart) updatedSpans.add(span.copy(start = newStart, end = newEnd))
    }

    if (insertedCount > 0 && (activeBold || activeItalic)) {
        updatedSpans.add(InlineStyleSpan(start = prefix, end = newSuffix, bold = activeBold, italic = activeItalic))
    }

    val removedNewlines = oldText.substring(prefix, oldSuffix).count { it == '\n' }
    val addedNewlines = newText.substring(prefix, newSuffix).count { it == '\n' }
    val lineDelta = addedNewlines - removedNewlines

    val updatedHeadings = if (lineDelta == 0) {
        oldDoc.lineHeadings
    } else {
        val editLineIndex = oldText.substring(0, prefix).count { it == '\n' }
        val map = mutableMapOf<Int, HeadingStyle>()
        oldDoc.lineHeadings.forEach { (lineIdx, style) ->
            when {
                lineIdx < editLineIndex -> map[lineIdx] = style
                lineIdx == editLineIndex -> {
                    val lineStartOffset = oldText.lastIndexOf('\n', (prefix - 1).coerceAtLeast(0))
                        .let { if (it == -1) 0 else it + 1 }
                    if (addedNewlines > 0 && prefix == lineStartOffset) {
                        map[lineIdx + lineDelta] = style
                    } else {
                        map[lineIdx] = style
                    }
                }
                else -> {
                    val shifted = lineIdx + lineDelta
                    if (shifted >= 0) map[shifted] = style
                }
            }
        }
        map
    }

    return RichNoteDocument(
        text = newText,
        spans = normalizeSpans(updatedSpans, newText.length),
        lineHeadings = updatedHeadings
    )
}

fun normalizeSpans(spans: List<InlineStyleSpan>, textLength: Int): List<InlineStyleSpan> {
    if (spans.isEmpty() || textLength == 0) return emptyList()
    val clamped = spans.mapNotNull { s ->
        val start = s.start.coerceIn(0, textLength)
        val end = s.end.coerceIn(0, textLength)
        if (end > start && (s.bold || s.italic || !s.link.isNullOrBlank())) s.copy(start = start, end = end) else null
    }
    if (clamped.isEmpty()) return emptyList()

    val points = clamped.flatMap { listOf(it.start, it.end) }.distinct().sorted()
    val merged = mutableListOf<InlineStyleSpan>()

    for (i in 0 until points.size - 1) {
        val segStart = points[i]
        val segEnd = points[i + 1]
        if (segEnd <= segStart) continue
        val covering = clamped.filter { it.start <= segStart && it.end >= segEnd }
        if (covering.isNotEmpty()) {
            val bold = covering.any { it.bold }
            val italic = covering.any { it.italic }
            val link = covering.lastOrNull { !it.link.isNullOrBlank() }?.link
            if (bold || italic || link != null) {
                val prev = merged.lastOrNull()
                if (prev != null && prev.end == segStart && prev.bold == bold && prev.italic == italic && prev.link == link) {
                    merged[merged.lastIndex] = prev.copy(end = segEnd)
                } else {
                    merged.add(InlineStyleSpan(segStart, segEnd, bold, italic, link))
                }
            }
        }
    }
    return merged
}

fun toggleSelectionInlineStyle(
    doc: RichNoteDocument,
    selection: TextRange,
    toggleBold: Boolean = false,
    toggleItalic: Boolean = false
): RichNoteDocument {
    val start = selection.min.coerceIn(0, doc.text.length)
    val end = selection.max.coerceIn(0, doc.text.length)
    if (end <= start) return doc

    val currentlyAllBold = doc.spans.any { it.start <= start && it.end >= end && it.bold }
    val currentlyAllItalic = doc.spans.any { it.start <= start && it.end >= end && it.italic }

    val result = mutableListOf<InlineStyleSpan>()
    for (span in doc.spans) {
        if (span.end <= start || span.start >= end) {
            result.add(span)
        } else {
            if (span.start < start) result.add(span.copy(end = start))
            if (span.end > end) result.add(span.copy(start = end))
            val overlapStart = max(span.start, start)
            val overlapEnd = min(span.end, end)
            val newBold = if (toggleBold) !currentlyAllBold else span.bold
            val newItalic = if (toggleItalic) !currentlyAllItalic else span.italic
            if (newBold || newItalic || span.link != null) {
                result.add(span.copy(start = overlapStart, end = overlapEnd, bold = newBold, italic = newItalic))
            }
        }
    }
    if (toggleBold && !currentlyAllBold) result.add(InlineStyleSpan(start, end, bold = true))
    if (toggleItalic && !currentlyAllItalic) result.add(InlineStyleSpan(start, end, italic = true))

    return doc.copy(spans = normalizeSpans(result, doc.text.length))
}

class WysiwygVisualTransformation(
    private val doc: RichNoteDocument,
    private val baseFontSizeSp: Float,
    private val accentColor: Color,
    private val mutedColor: Color
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val styled = buildAnnotatedString {
            append(raw)
            var lineStart = 0
            var lineIdx = 0
            for (line in raw.split('\n')) {
                val lineEnd = lineStart + line.length
                if (lineEnd > lineStart) {
                    when (doc.lineHeadings[lineIdx] ?: HeadingStyle.TEXT) {
                        HeadingStyle.LARGE -> addStyle(
                            SpanStyle(
                                fontSize = (baseFontSizeSp + 7f).sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-0.3).sp
                            ),
                            lineStart,
                            lineEnd
                        )
                        HeadingStyle.SMALL -> addStyle(
                            SpanStyle(
                                fontSize = (baseFontSizeSp + 3f).sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-0.15).sp
                            ),
                            lineStart,
                            lineEnd
                        )
                        HeadingStyle.TEXT -> {}
                    }
                    when {
                        line.startsWith("• ") -> addStyle(
                            SpanStyle(color = accentColor, fontWeight = FontWeight.Bold),
                            lineStart,
                            (lineStart + 2).coerceAtMost(lineEnd)
                        )
                        line.startsWith("☐ ") -> addStyle(
                            SpanStyle(color = mutedColor, fontSize = (baseFontSizeSp + 2f).sp),
                            lineStart,
                            (lineStart + 2).coerceAtMost(lineEnd)
                        )
                        line.startsWith("☑ ") -> {
                            addStyle(
                                SpanStyle(color = accentColor, fontSize = (baseFontSizeSp + 2f).sp),
                                lineStart,
                                (lineStart + 2).coerceAtMost(lineEnd)
                            )
                            if (lineEnd > lineStart + 2) {
                                addStyle(
                                    SpanStyle(color = mutedColor, textDecoration = TextDecoration.LineThrough),
                                    lineStart + 2,
                                    lineEnd
                                )
                            }
                        }
                    }
                }
                lineStart = lineEnd + 1
                lineIdx++
            }
            for (span in doc.spans) {
                val s = span.start.coerceIn(0, raw.length)
                val e = span.end.coerceIn(0, raw.length)
                if (e > s) {
                    if (span.bold) addStyle(SpanStyle(fontWeight = FontWeight.Bold), s, e)
                    if (span.italic) addStyle(SpanStyle(fontStyle = FontStyle.Italic), s, e)
                    if (!span.link.isNullOrBlank()) {
                        addStyle(SpanStyle(color = accentColor, textDecoration = TextDecoration.Underline), s, e)
                    }
                }
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}

fun formatRelativeDate(millis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - millis
    val calNow = Calendar.getInstance()
    val calNote = Calendar.getInstance().apply { timeInMillis = millis }

    return when {
        diff < 60_000L -> "Just now"
        calNow.get(Calendar.YEAR) == calNote.get(Calendar.YEAR) &&
            calNow.get(Calendar.DAY_OF_YEAR) == calNote.get(Calendar.DAY_OF_YEAR) ->
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
        calNow.get(Calendar.YEAR) == calNote.get(Calendar.YEAR) &&
            calNow.get(Calendar.DAY_OF_YEAR) - calNote.get(Calendar.DAY_OF_YEAR) == 1 -> "Yesterday"
        calNow.get(Calendar.YEAR) == calNote.get(Calendar.YEAR) ->
            SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(millis))
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(millis))
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val db = NotesDatabase(applicationContext)
        val prefs = getSharedPreferences("notes_settings", Context.MODE_PRIVATE)

        setContent {
            var isDarkTheme by remember { mutableStateOf(prefs.getBoolean("dark_mode", false)) }
            var fontScaleIndex by remember { mutableStateOf(prefs.getInt("font_scale", 1)) }

            val colorScheme = if (isDarkTheme) {
                darkColorScheme(
                    primary = Color(0xFFE6E1D6),
                    onPrimary = Color(0xFF141413),
                    secondary = Color(0xFFA8A29E),
                    background = Color(0xFF141413),
                    surface = Color(0xFF1C1C1A),
                    surfaceVariant = Color(0xFF262624),
                    onBackground = Color(0xFFECEAE4),
                    onSurface = Color(0xFFECEAE4),
                    onSurfaceVariant = Color(0xFF9C9891),
                    outline = Color(0xFF2E2E2B),
                    error = Color(0xFFF28B82)
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF1F1F1D),
                    onPrimary = Color(0xFFFFFFFF),
                    secondary = Color(0xFF57534E),
                    background = Color(0xFFFAF9F5),
                    surface = Color(0xFFFFFFFF),
                    surfaceVariant = Color(0xFFF1EFEA),
                    onBackground = Color(0xFF191918),
                    onSurface = Color(0xFF191918),
                    onSurfaceVariant = Color(0xFF78756F),
                    outline = Color(0xFFE6E4DD),
                    error = Color(0xFFD93025)
                )
            }

            val baseFontSizeSp = when (fontScaleIndex) {
                0 -> 15f
                2 -> 18f
                else -> 16.5f
            }

            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NotesApp(
                        db = db,
                        isDarkTheme = isDarkTheme,
                        onToggleDarkTheme = { enabled ->
                            isDarkTheme = enabled
                            prefs.edit().putBoolean("dark_mode", enabled).apply()
                        },
                        fontScaleIndex = fontScaleIndex,
                        onSelectFontScale = { scale ->
                            fontScaleIndex = scale
                            prefs.edit().putInt("font_scale", scale).apply()
                        },
                        baseFontSizeSp = baseFontSizeSp
                    )
                }
            }
        }
    }
}

@Composable
fun NotesApp(
    db: NotesDatabase,
    isDarkTheme: Boolean,
    onToggleDarkTheme: (Boolean) -> Unit,
    fontScaleIndex: Int,
    onSelectFontScale: (Int) -> Unit,
    baseFontSizeSp: Float
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var notes by remember { mutableStateOf(db.getAllNotes()) }
    var activeNoteId by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    var noteToRename by remember { mutableStateOf<Note?>(null) }
    var noteToDelete by remember { mutableStateOf<Note?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    fun refreshNotes() {
        notes = db.getAllNotes()
    }

    fun handleDeleteWithUndo(target: Note) {
        db.deleteNote(target.id)
        if (activeNoteId == target.id) activeNoteId = null
        refreshNotes()
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = "Note deleted",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                db.upsertNote(target)
                refreshNotes()
            }
        }
    }

    val activeNote = remember(notes, activeNoteId) {
        activeNoteId?.let { id -> notes.firstOrNull { it.id == id } }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    shape = RoundedCornerShape(10.dp),
                    containerColor = MaterialTheme.colorScheme.onBackground,
                    contentColor = MaterialTheme.colorScheme.background,
                    actionColor = MaterialTheme.colorScheme.background
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { rootPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(rootPadding)
        ) {
            if (activeNote != null) {
                NoteEditorScreen(
                    note = activeNote,
                    baseFontSizeSp = baseFontSizeSp,
                    onAutoSave = { updated ->
                        db.upsertNote(updated)
                        refreshNotes()
                    },
                    onBack = {
                        refreshNotes()
                        activeNoteId = null
                    },
                    onTogglePin = { current ->
                        val updated = current.copy(
                            isPinned = !current.isPinned,
                            updatedAt = System.currentTimeMillis()
                        )
                        db.upsertNote(updated)
                        refreshNotes()
                    },
                    onRequestRename = { current -> noteToRename = current },
                    onRequestDelete = { current -> noteToDelete = current }
                )
            } else {
                NotesHomeScreen(
                    notes = notes,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    onOpenNote = { note -> activeNoteId = note.id },
                    onCreateNote = {
                        val fresh = Note(title = "", document = RichNoteDocument())
                        db.upsertNote(fresh)
                        refreshNotes()
                        activeNoteId = fresh.id
                    },
                    onTogglePin = { note ->
                        val updated = note.copy(
                            isPinned = !note.isPinned,
                            updatedAt = System.currentTimeMillis()
                        )
                        db.upsertNote(updated)
                        refreshNotes()
                    },
                    onRequestRename = { note -> noteToRename = note },
                    onRequestDelete = { note -> noteToDelete = note },
                    onOpenSettings = { showSettingsDialog = true }
                )
            }
        }
    }

    if (noteToRename != null) {
        val target = noteToRename!!
        RenameNoteDialog(
            initialTitle = target.title,
            onDismiss = { noteToRename = null },
            onConfirm = { newTitle ->
                val updated = target.copy(
                    title = newTitle.trim(),
                    updatedAt = System.currentTimeMillis()
                )
                db.upsertNote(updated)
                refreshNotes()
                noteToRename = null
            }
        )
    }

    if (noteToDelete != null) {
        val target = noteToDelete!!
        DeleteConfirmationDialog(
            noteTitle = target.title.ifBlank { "Untitled" },
            onDismiss = { noteToDelete = null },
            onConfirmDelete = {
                val deleting = target
                noteToDelete = null
                handleDeleteWithUndo(deleting)
            }
        )
    }

    if (showSettingsDialog) {
        SettingsDialog(
            isDarkTheme = isDarkTheme,
            onToggleDarkTheme = onToggleDarkTheme,
            fontScaleIndex = fontScaleIndex,
            onSelectFontScale = onSelectFontScale,
            onDismiss = { showSettingsDialog = false }
        )
    }
}

@Composable
fun NotesHomeScreen(
    notes: List<Note>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onOpenNote: (Note) -> Unit,
    onCreateNote: () -> Unit,
    onTogglePin: (Note) -> Unit,
    onRequestRename: (Note) -> Unit,
    onRequestDelete: (Note) -> Unit,
    onOpenSettings: () -> Unit
) {
    val filteredNotes = remember(notes, searchQuery) {
        val q = searchQuery.trim().lowercase()
        val matching = if (q.isEmpty()) {
            notes
        } else {
            notes.filter { note ->
                note.title.lowercase().contains(q) || note.document.text.lowercase().contains(q)
            }
        }
        matching.sortedByDescending { it.updatedAt }
    }

    val pinnedNotes = remember(filteredNotes) { filteredNotes.filter { it.isPinned } }
    val unpinnedNotes = remember(filteredNotes) { filteredNotes.filter { !it.isPinned } }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Notes",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.6).sp,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onCreateNote,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "New note",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Search notes",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            singleLine = true,
                            textStyle = TextStyle(
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { onSearchQueryChange("") }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (filteredNotes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    ) {
                        Text(
                            text = if (searchQuery.isNotBlank()) "No matching notes" else "No notes yet",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (searchQuery.isNotBlank()) "Try a different search term." else "Create a note to start writing.",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (searchQuery.isBlank()) {
                            Spacer(Modifier.height(18.dp))
                            OutlinedButton(
                                onClick = onCreateNote,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("New note")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 40.dp)
                ) {
                    if (pinnedNotes.isNotEmpty()) {
                        item(key = "header_pinned") { SectionHeader("Pinned") }
                        items(pinnedNotes, key = { "pinned_${it.id}" }) { note ->
                            NoteListItem(
                                note = note,
                                onClick = { onOpenNote(note) },
                                onTogglePin = { onTogglePin(note) },
                                onRename = { onRequestRename(note) },
                                onDelete = { onRequestDelete(note) }
                            )
                        }
                        item(key = "spacer_pinned") { Spacer(Modifier.height(20.dp)) }
                    }

                    item(key = "header_all_notes") { SectionHeader("All Notes") }
                    if (unpinnedNotes.isEmpty() && pinnedNotes.isNotEmpty()) {
                        item(key = "all_pinned_hint") {
                            Text(
                                text = "All of your notes are pinned above.",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp)
                            )
                        }
                    } else {
                        items(unpinnedNotes, key = { "note_${it.id}" }) { note ->
                            NoteListItem(
                                note = note,
                                onClick = { onOpenNote(note) },
                                onTogglePin = { onTogglePin(note) },
                                onRename = { onRequestRename(note) },
                                onDelete = { onRequestDelete(note) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp, top = 4.dp)
    )
}

@Composable
fun NoteListItem(
    note: Note,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuExpanded = true }
                )
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = note.title.ifBlank { "Untitled" },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (note.isPinned) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = "Pinned",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    text = note.previewText.ifBlank { "No additional text" },
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    text = formatRelativeDate(note.updatedAt),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreHoriz,
                        contentDescription = "Note actions",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                NoteActionDropdownMenu(
                    expanded = menuExpanded,
                    isPinned = note.isPinned,
                    onDismiss = { menuExpanded = false },
                    onTogglePin = {
                        menuExpanded = false
                        onTogglePin()
                    },
                    onRename = {
                        menuExpanded = false
                        onRename()
                    },
                    onDelete = {
                        menuExpanded = false
                        onDelete()
                    }
                )
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
            thickness = 0.5.dp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

@Composable
fun NoteActionDropdownMenu(
    expanded: Boolean,
    isPinned: Boolean,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        DropdownMenuItem(
            text = { Text(if (isPinned) "Unpin" else "Pin") },
            leadingIcon = {
                Icon(
                    imageVector = if (isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = onTogglePin
        )
        DropdownMenuItem(
            text = { Text("Rename") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = onRename
        )
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
        )
        DropdownMenuItem(
            text = {
                Text(
                    text = "Delete",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = onDelete
        )
    }
}

enum class SaveStatus { SAVED, SAVING }

@Composable
fun NoteEditorScreen(
    note: Note,
    baseFontSizeSp: Float,
    onAutoSave: (Note) -> Unit,
    onBack: () -> Unit,
    onTogglePin: (Note) -> Unit,
    onRequestRename: (Note) -> Unit,
    onRequestDelete: (Note) -> Unit
) {
    var title by remember(note.id) { mutableStateOf(note.title) }
    var doc by remember(note.id) { mutableStateOf(note.document) }
    var textFieldValue by remember(note.id) {
        mutableStateOf(
            TextFieldValue(
                text = note.document.text,
                selection = TextRange(note.document.text.length)
            )
        )
    }

    LaunchedEffect(note.title) {
        if (note.title != title) title = note.title
    }

    var saveStatus by remember { mutableStateOf(SaveStatus.SAVED) }
    var showFormattingBar by remember { mutableStateOf(false) }
    var activeBold by remember { mutableStateOf(false) }
    var activeItalic by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }

    val bodyFocusRequester = remember { FocusRequester() }
    val titleFocusRequester = remember { FocusRequester() }

    LaunchedEffect(note.id) {
        if (note.title.isEmpty() && note.document.text.isEmpty()) {
            delay(100)
            runCatching { titleFocusRequester.requestFocus() }
        }
    }

    fun buildCurrentNote(): Note = note.copy(
        title = title.trim(),
        document = doc,
        updatedAt = System.currentTimeMillis()
    )

    fun triggerImmediateSave(updatedDoc: RichNoteDocument = doc, updatedTitle: String = title) {
        saveStatus = SaveStatus.SAVING
        onAutoSave(
            note.copy(
                title = updatedTitle.trim(),
                document = updatedDoc,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    LaunchedEffect(title, doc) {
        if (saveStatus == SaveStatus.SAVING) {
            delay(400)
            saveStatus = SaveStatus.SAVED
        }
    }

    BackHandler {
        onAutoSave(buildCurrentNote())
        onBack()
    }

    val currentCursor = textFieldValue.selection.start.coerceIn(0, textFieldValue.text.length)
    val currentLineIndex = remember(textFieldValue.text, currentCursor) {
        textFieldValue.text.substring(0, currentCursor).count { it == '\n' }
    }
    val currentHeadingStyle = doc.lineHeadings[currentLineIndex] ?: HeadingStyle.TEXT

    val isSelectionBold = remember(doc.spans, textFieldValue.selection, activeBold) {
        val sel = textFieldValue.selection
        if (!sel.collapsed) {
            doc.spans.any { it.start <= sel.min && it.end >= sel.max && it.bold }
        } else {
            activeBold || doc.spans.any { it.start < sel.start && it.end >= sel.start && it.bold }
        }
    }

    val isSelectionItalic = remember(doc.spans, textFieldValue.selection, activeItalic) {
        val sel = textFieldValue.selection
        if (!sel.collapsed) {
            doc.spans.any { it.start <= sel.min && it.end >= sel.max && it.italic }
        } else {
            activeItalic || doc.spans.any { it.start < sel.start && it.end >= sel.start && it.italic }
        }
    }

    val isToolbarVisible = showFormattingBar || !textFieldValue.selection.collapsed

    val accentColor = MaterialTheme.colorScheme.primary
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val visualTransformation = remember(doc, baseFontSizeSp, accentColor, mutedColor) {
        WysiwygVisualTransformation(
            doc = doc,
            baseFontSizeSp = baseFontSizeSp,
            accentColor = accentColor,
            mutedColor = mutedColor
        )
    }

    fun toggleLinePrefix(targetPrefix: String) {
        val raw = textFieldValue.text
        val cursor = textFieldValue.selection.start.coerceIn(0, raw.length)
        val lineStart = raw.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0))
            .let { if (it == -1) 0 else it + 1 }
        val lineEnd = raw.indexOf('\n', cursor).let { if (it == -1) raw.length else it }
        val currentLine = raw.substring(lineStart, lineEnd)

        val existingPrefixLen = when {
            currentLine.startsWith("• ") || currentLine.startsWith("☐ ") || currentLine.startsWith("☑ ") -> 2
            else -> 0
        }
        val alreadyHasTarget = when (targetPrefix) {
            "☐ " -> currentLine.startsWith("☐ ") || currentLine.startsWith("☑ ")
            else -> currentLine.startsWith(targetPrefix)
        }

        val replacementLine = if (alreadyHasTarget) {
            currentLine.substring(existingPrefixLen)
        } else {
            targetPrefix + currentLine.substring(existingPrefixLen)
        }

        val newText = raw.substring(0, lineStart) + replacementLine + raw.substring(lineEnd)
        val lengthDelta = replacementLine.length - currentLine.length
        val newCursor = (cursor + lengthDelta).coerceIn(lineStart, lineStart + replacementLine.length)

        val updatedDoc = adjustDocumentOnEdit(doc, newText, activeBold = false, activeItalic = false)
        doc = updatedDoc
        textFieldValue = TextFieldValue(text = newText, selection = TextRange(newCursor))
        triggerImmediateSave(updatedDoc = updatedDoc)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        onAutoSave(buildCurrentNote())
                        onBack()
                    }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to notes",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (saveStatus == SaveStatus.SAVING) "Saving…" else "Saved",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.padding(end = 6.dp)
                    )

                    IconButton(onClick = { showFormattingBar = !showFormattingBar }) {
                        Icon(
                            imageVector = Icons.Default.FormatSize,
                            contentDescription = "Format text",
                            tint = if (isToolbarVisible) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreHoriz,
                                contentDescription = "Note options",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        NoteActionDropdownMenu(
                            expanded = showMoreMenu,
                            isPinned = note.isPinned,
                            onDismiss = { showMoreMenu = false },
                            onTogglePin = {
                                showMoreMenu = false
                                onTogglePin(buildCurrentNote())
                            },
                            onRename = {
                                showMoreMenu = false
                                onRequestRename(buildCurrentNote())
                            },
                            onDelete = {
                                showMoreMenu = false
                                onRequestDelete(buildCurrentNote())
                            }
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (title.isEmpty()) {
                        Text(
                            text = "Title",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.4).sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        )
                    }
                    BasicTextField(
                        value = title,
                        onValueChange = { newTitle ->
                            title = newTitle
                            triggerImmediateSave(updatedTitle = newTitle)
                        },
                        textStyle = TextStyle(
                            fontSize = 26.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 32.sp,
                            letterSpacing = (-0.4).sp,
                            color = MaterialTheme.colorScheme.onBackground
                        ),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { runCatching { bodyFocusRequester.requestFocus() } }
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(titleFocusRequester)
                    )
                }

                Spacer(Modifier.height(18.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 420.dp)
                ) {
                    if (textFieldValue.text.isEmpty()) {
                        Text(
                            text = "Start writing…",
                            fontSize = baseFontSizeSp.sp,
                            lineHeight = (baseFontSizeSp * 1.6f).sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        )
                    }

                    BasicTextField(
                        value = textFieldValue,
                        onValueChange = { incoming ->
                            val oldText = textFieldValue.text
                            val newText = incoming.text

                            if (newText == oldText && incoming.selection.collapsed && incoming.selection != textFieldValue.selection) {
                                val tapOffset = incoming.selection.start.coerceIn(0, newText.length)
                                val lineStart = newText.lastIndexOf('\n', (tapOffset - 1).coerceAtLeast(0))
                                    .let { if (it == -1) 0 else it + 1 }
                                val lineEnd = newText.indexOf('\n', tapOffset).let { if (it == -1) newText.length else it }
                                val line = newText.substring(lineStart, lineEnd)

                                if ((line.startsWith("☐ ") || line.startsWith("☑ ")) && tapOffset <= lineStart + 2) {
                                    val toggledPrefix = if (line.startsWith("☐ ")) "☑ " else "☐ "
                                    val updatedText = newText.substring(0, lineStart) + toggledPrefix + line.substring(2)
                                    val updatedDoc = doc.copy(text = updatedText)
                                    doc = updatedDoc
                                    textFieldValue = TextFieldValue(
                                        text = updatedText,
                                        selection = TextRange((lineStart + 2).coerceAtMost(updatedText.length))
                                    )
                                    triggerImmediateSave(updatedDoc = updatedDoc)
                                    return@BasicTextField
                                }
                            }

                            if (newText.length == oldText.length + 1 && incoming.selection.collapsed) {
                                val insIdx = (incoming.selection.start - 1).coerceIn(0, newText.lastIndex)
                                if (newText[insIdx] == '\n') {
                                    val prevLineStart = newText.lastIndexOf('\n', (insIdx - 1).coerceAtLeast(0))
                                        .let { if (it == -1) 0 else it + 1 }
                                    val prevLine = newText.substring(prevLineStart, insIdx)

                                    when {
                                        prevLine == "• " || prevLine == "☐ " || prevLine == "☑ " -> {
                                            val cleanedText = newText.substring(0, prevLineStart) + newText.substring(insIdx + 1)
                                            val updatedDoc = adjustDocumentOnEdit(doc, cleanedText, activeBold, activeItalic)
                                            doc = updatedDoc
                                            textFieldValue = TextFieldValue(text = cleanedText, selection = TextRange(prevLineStart))
                                            triggerImmediateSave(updatedDoc = updatedDoc)
                                            return@BasicTextField
                                        }
                                        prevLine.startsWith("• ") -> {
                                            val autoText = newText.substring(0, insIdx + 1) + "• " + newText.substring(insIdx + 1)
                                            val updatedDoc = adjustDocumentOnEdit(doc, autoText, activeBold, activeItalic)
                                            doc = updatedDoc
                                            textFieldValue = TextFieldValue(text = autoText, selection = TextRange(insIdx + 3))
                                            triggerImmediateSave(updatedDoc = updatedDoc)
                                            return@BasicTextField
                                        }
                                        prevLine.startsWith("☐ ") || prevLine.startsWith("☑ ") -> {
                                            val autoText = newText.substring(0, insIdx + 1) + "☐ " + newText.substring(insIdx + 1)
                                            val updatedDoc = adjustDocumentOnEdit(doc, autoText, activeBold, activeItalic)
                                            doc = updatedDoc
                                            textFieldValue = TextFieldValue(text = autoText, selection = TextRange(insIdx + 3))
                                            triggerImmediateSave(updatedDoc = updatedDoc)
                                            return@BasicTextField
                                        }
                                    }
                                }
                            }

                            if (newText != oldText) {
                                val updatedDoc = adjustDocumentOnEdit(doc, newText, activeBold, activeItalic)
                                doc = updatedDoc
                                textFieldValue = incoming
                                triggerImmediateSave(updatedDoc = updatedDoc)
                            } else {
                                textFieldValue = incoming
                            }
                        },
                        textStyle = TextStyle(
                            fontSize = baseFontSizeSp.sp,
                            lineHeight = (baseFontSizeSp * 1.6f).sp,
                            color = MaterialTheme.colorScheme.onBackground
                        ),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        visualTransformation = visualTransformation,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 420.dp)
                            .focusRequester(bodyFocusRequester)
                    )
                }

                Spacer(Modifier.height(64.dp))
            }
        }

        AnimatedVisibility(
            visible = isToolbarVisible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                FormattingToolbar(
                    currentHeading = currentHeadingStyle,
                    isBoldActive = isSelectionBold,
                    isItalicActive = isSelectionItalic,
                    onSelectHeading = { chosenStyle ->
                        val updatedMap = doc.lineHeadings.toMutableMap()
                        if (chosenStyle == HeadingStyle.TEXT) {
                            updatedMap.remove(currentLineIndex)
                        } else {
                            updatedMap[currentLineIndex] = chosenStyle
                        }
                        val updatedDoc = doc.copy(lineHeadings = updatedMap)
                        doc = updatedDoc
                        triggerImmediateSave(updatedDoc = updatedDoc)
                    },
                    onToggleBold = {
                        val sel = textFieldValue.selection
                        if (!sel.collapsed) {
                            val updatedDoc = toggleSelectionInlineStyle(doc, sel, toggleBold = true)
                            doc = updatedDoc
                            triggerImmediateSave(updatedDoc = updatedDoc)
                        } else {
                            activeBold = !activeBold
                        }
                    },
                    onToggleItalic = {
                        val sel = textFieldValue.selection
                        if (!sel.collapsed) {
                            val updatedDoc = toggleSelectionInlineStyle(doc, sel, toggleItalic = true)
                            doc = updatedDoc
                            triggerImmediateSave(updatedDoc = updatedDoc)
                        } else {
                            activeItalic = !activeItalic
                        }
                    },
                    onToggleBulletList = { toggleLinePrefix("• ") },
                    onToggleChecklist = { toggleLinePrefix("☐ ") },
                    onOpenLinkDialog = { showLinkDialog = true }
                )
            }
        }
    }

    if (showLinkDialog) {
        val sel = textFieldValue.selection
        val selectedLabel = if (!sel.collapsed) textFieldValue.text.substring(sel.min, sel.max) else ""

        InsertLinkDialog(
            initialLabel = selectedLabel,
            onDismiss = { showLinkDialog = false },
            onInsertLink = { label, url ->
                val raw = textFieldValue.text
                val insertStart = sel.min.coerceIn(0, raw.length)
                val insertEnd = sel.max.coerceIn(0, raw.length)
                val displayLabel = label.ifBlank { url }
                val newText = raw.substring(0, insertStart) + displayLabel + raw.substring(insertEnd)
                val adjustedDoc = adjustDocumentOnEdit(doc, newText, activeBold = false, activeItalic = false)
                val linkedSpans = normalizeSpans(
                    adjustedDoc.spans + InlineStyleSpan(
                        start = insertStart,
                        end = insertStart + displayLabel.length,
                        link = url
                    ),
                    newText.length
                )
                val finalDoc = adjustedDoc.copy(spans = linkedSpans)
                doc = finalDoc
                textFieldValue = TextFieldValue(
                    text = newText,
                    selection = TextRange(insertStart + displayLabel.length)
                )
                triggerImmediateSave(updatedDoc = finalDoc)
                showLinkDialog = false
            }
        )
    }
}

@Composable
fun FormattingToolbar(
    currentHeading: HeadingStyle,
    isBoldActive: Boolean,
    isItalicActive: Boolean,
    onSelectHeading: (HeadingStyle) -> Unit,
    onToggleBold: () -> Unit,
    onToggleItalic: () -> Unit,
    onToggleBulletList: () -> Unit,
    onToggleChecklist: () -> Unit,
    onOpenLinkDialog: () -> Unit
) {
    var styleMenuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .widthIn(max = 560.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { styleMenuExpanded = true }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = currentHeading.label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Choose text style",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            DropdownMenu(
                expanded = styleMenuExpanded,
                onDismissRequest = { styleMenuExpanded = false },
                shape = RoundedCornerShape(12.dp),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                DropdownMenuItem(
                    text = { Text(HeadingStyle.TEXT.label, fontSize = 15.sp, fontWeight = FontWeight.Normal) },
                    onClick = {
                        styleMenuExpanded = false
                        onSelectHeading(HeadingStyle.TEXT)
                    }
                )
                DropdownMenuItem(
                    text = { Text(HeadingStyle.SMALL.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
                    onClick = {
                        styleMenuExpanded = false
                        onSelectHeading(HeadingStyle.SMALL)
                    }
                )
                DropdownMenuItem(
                    text = { Text(HeadingStyle.LARGE.label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
                    onClick = {
                        styleMenuExpanded = false
                        onSelectHeading(HeadingStyle.LARGE)
                    }
                )
            }
        }

        ToolbarIconButton(Icons.Default.FormatBold, "Bold", isBoldActive, onToggleBold)
        ToolbarIconButton(Icons.Default.FormatItalic, "Italic", isItalicActive, onToggleItalic)
        ToolbarIconButton(Icons.AutoMirrored.Filled.FormatListBulleted, "Bulleted list", false, onToggleBulletList)
        ToolbarIconButton(Icons.Default.CheckBox, "Checklist", false, onToggleChecklist)
        ToolbarIconButton(Icons.Default.Link, "Insert link", false, onOpenLinkDialog)
    }
}

@Composable
fun ToolbarIconButton(
    icon: ImageVector,
    contentDescription: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (isActive) MaterialTheme.colorScheme.primary else Color.Transparent,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun RenameNoteDialog(
    initialTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var titleText by remember { mutableStateOf(initialTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Rename note", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(
                value = titleText,
                onValueChange = { titleText = it },
                placeholder = { Text("Note title") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(titleText) }) {
                Text("Save", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun DeleteConfirmationDialog(
    noteTitle: String,
    onDismiss: () -> Unit,
    onConfirmDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Delete note?", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Text(
                text = "“$noteTitle” will be deleted.",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirmDelete) {
                Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun InsertLinkDialog(
    initialLabel: String,
    onDismiss: () -> Unit,
    onInsertLink: (label: String, url: String) -> Unit
) {
    var label by remember { mutableStateOf(initialLabel) }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Add link", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Text") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Link destination") },
                    placeholder = { Text("https://example.com") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank() || label.isNotBlank(),
                onClick = { onInsertLink(label.trim(), url.trim()) }
            ) {
                Text("Insert", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun SettingsDialog(
    isDarkTheme: Boolean,
    onToggleDarkTheme: (Boolean) -> Unit,
    fontScaleIndex: Int,
    onSelectFontScale: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Settings", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Dark appearance",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Use a dark writing canvas",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = isDarkTheme, onCheckedChange = onToggleDarkTheme)
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Reading & writing text size",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Small", "Default", "Large").forEachIndexed { idx, label ->
                            FilterChip(
                                selected = fontScaleIndex == idx,
                                onClick = { onSelectFontScale(idx) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", fontWeight = FontWeight.SemiBold)
            }
        }
    )
}
