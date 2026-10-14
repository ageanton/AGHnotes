
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

// ============================================================================
// DATA MODEL & RICH DOCUMENT SERIALIZATION
// ============================================================================

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
            .map { line ->
                line.removePrefix("• ")
                    .removePrefix("☐ ")
                    .removePrefix("☑ ")
                    .trim()
            }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    fun serialize(): String {
        val spansArray = JSONArray()
        spans.filter { it.end > it.start && (it.bold || it.italic || !it.link.isNullOrBlank()) }
            .forEach { span ->
                spansArray.put(
                    JSONObject().apply {
                        put("s", span.start)
                        put("e", span.end)
                        if (span.bold) put("b", true)
                        if (span.italic) put("i", true)
                        if (!span.link.isNullOrBlank()) put("l", span.link)
                    }
                )
            }

        val headingsObj = JSONObject()
        lineHeadings.forEach { (lineIdx, style) ->
            if (style != HeadingStyle.TEXT) {
                headingsObj.put(lineIdx.toString(), style.name)
            }
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
                                val styleName = headingsObj.optString(key)
                                val style = runCatching { HeadingStyle.valueOf(styleName) }
                                    .getOrDefault(HeadingStyle.TEXT)
                                if (style != HeadingStyle.TEXT) {
                                    headings[lineIdx] = style
                                }
                            }
                        }
                        return RichNoteDocument(text = text, spans = spans, lineHeadings = headings)
                    }
                } catch (_: Exception) {
                    // Fall through to legacy parser if corrupted JSON
                }
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
                    trimmed.startsWith("- [ ] ") -> {
                        working = "☐ " + trimmed.removePrefix("- [ ] ")
                    }
                    trimmed.startsWith("- [x] ") || trimmed.startsWith("- [X] ") -> {
                        working = "☑ " + trimmed.substring(6)
                    }
                    trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                        working = "• " + trimmed.substring(2)
                    }
                    trimmed.startsWith("> ") -> {
                        working = trimmed.removePrefix("> ")
                    }
                }

                // Strip [[WikiLinks]] brackets while keeping inner text
                working = working.replace(Regex("""\[\[([^\[\]]+)\]\]"""), "$1")

                // Convert **bold** into InlineStyleSpan
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
    val previewText: String
        get() = document.toPlainPreview()
}

// ============================================================================
// LOCAL SQLITE DATABASE (BACKWARD-COMPATIBLE WITH EXISTING INSTALLS)
// ============================================================================

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
        if (!db.isReadOnly) {
            cleanupLegacyPromotionalNotes(db)
        }
    }

    private fun cleanupLegacyPromotionalNotes(db: SQLiteDatabase) {
        try {
            // Remove untouched promotional v1.0 starter notes and unlock any previously locked notes
            db.execSQL(
                """
                DELETE FROM notes 
                WHERE title IN ('Welcome to Monolith Vault', 'Zero-Cloud Architecture', 'Quick Capture Scratchpad')
                """.trimIndent()
            )
            db.execSQL("UPDATE notes SET is_locked = 0 WHERE is_locked != 0")
            db.execSQL("DELETE FROM notes WHERE is_trashed = 1")
        } catch (_: Exception) {
        }
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
        } catch (_: Exception) {
        }
    }

    fun deleteNote(id: String) {
        try {
            writableDatabase.delete("notes", "id = ?", arrayOf(id))
        } catch (_: Exception) {
        }
    }

    fun getAllNotes(): List<Note> {
        val list = mutableListOf<Note>()
        try {
            val cursor = readableDatabase.query(
                "notes",
                null,
                "is_trashed = 0",
                null,
                null,
                null,
                "updated_at DESC"
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
        } catch (_: Exception) {
        }
        return list.sortedByDescending { it.updatedAt }
    }
}

// ============================================================================
// FORMATTING ENGINE & WYSIWYG VISUAL TRANSFORMATION
// ============================================================================

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
    while (prefix < minLen && oldText[prefix] == newText[prefix]) {
        prefix++
    }

    var oldSuffix = oldText.length
    var newSuffix = newText.length
    while (oldSuffix > prefix && newSuffix > prefix && oldText[oldSuffix - 1] == newText[newSuffix - 1]) {
        oldSuffix--
        newSuffix--
    }

    val delta = newText.length - oldText.length
    val insertedCount = newSuffix - prefix

    // 1. Shift or expand inline spans
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
        if (newEnd > newStart) {
            updatedSpans.add(span.copy(start = newStart, end = newEnd))
        }
    }

    if (insertedCount > 0 && (activeBold || activeItalic)) {
        updatedSpans.add(
            InlineStyleSpan(
                start = prefix,
                end = newSuffix,
                bold = activeBold,
                italic = activeItalic
            )
        )
    }

    // 2. Shift line heading styles if newlines were inserted or deleted
    val oldRemovedSegment = oldText.substring(prefix, oldSuffix)
    val newInsertedSegment = newText.substring(prefix, newSuffix)
    val removedNewlines = oldRemovedSegment.count { it == '\n' }
    val addedNewlines = newInsertedSegment.count { it == '\n' }
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
                    // If Enter was pressed at the very start of a heading line, push the heading down
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
        if (end > start && (s.bold || s.italic || !s.link.isNullOrBlank())) {
            s.copy(start = start, end = end)
        } else null
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
            if (span.start < start) {
                result.add(span.copy(end = start))
            }
            if (span.end > end) {
                result.add(span.copy(start = end))
            }
            val overlapStart = max(span.start, start)
            val overlapEnd = min(span.end, end)
            val newBold = if (toggleBold) !currentlyAllBold else span.bold
            val newItalic = if (toggleItalic) !currentlyAllItalic else span.italic
            if (newBold || newItalic || span.link != null) {
                result.add(
                    span.copy(
                        start = overlapStart,
                        end = overlapEnd,
                        bold = newBold,
                        italic = newItalic
                    )
                )
            }
        }
    }

    if (toggleBold && !currentlyAllBold) {
        result.add(InlineStyleSpan(start, end, bold = true))
    }
    if (toggleItalic && !currentlyAllItalic) {
        result.add(InlineStyleSpan(start, end, italic = true))
    }

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

            // 1. Apply line-level headings, bullets, and checklist styles
            var lineStart = 0
            var lineIdx = 0
            val lines = raw.split('\n')
            for (line in lines) {
                val lineEnd = lineStart + line.length
                if (lineEnd > lineStart) {
                    when (doc.lineHeadings[lineIdx] ?: HeadingStyle.TEXT) {
                        HeadingStyle.LARGE -> {
                            addStyle(
                                SpanStyle(
                                    fontSize = (baseFontSizeSp + 7f).sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = (-0.3).sp
                                ),
                                lineStart,
                                lineEnd
                            )
                        }
                        HeadingStyle.SMALL -> {
                            addStyle(
                                SpanStyle(
                                    fontSize = (baseFontSizeSp + 3f).sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = (-0.15).sp
                                ),
                                lineStart,
                                lineEnd
                            )
                        }
                        HeadingStyle.TEXT -> {}
                    }

                    when {
                        line.startsWith("• ") -> {
                            addStyle(
                                SpanStyle(
                                    color = accentColor,
                                    fontWeight = FontWeight.Bold
                                ),
                                lineStart,
                                (lineStart + 2).coerceAtMost(lineEnd)
                            )
                        }
                        line.startsWith("☐ ") -> {
                            addStyle(
                                SpanStyle(
                                    color = mutedColor,
                                    fontSize = (baseFontSizeSp + 2f).sp
                                ),
                                lineStart,
                                (lineStart + 2).coerceAtMost(lineEnd)
                            )
                        }
                        line.startsWith("☑ ") -> {
                            addStyle(
                                SpanStyle(
                                    color = accentColor,
                                    fontSize = (baseFontSizeSp + 2f).sp
                                ),
                                lineStart,
                                (lineStart + 2).coerceAtMost(lineEnd)
                            )
                            if (lineEnd > lineStart + 2) {
                                addStyle(
                                    SpanStyle(
                                        color = mutedColor,
                                        textDecoration = TextDecoration.LineThrough
                                    ),
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

            // 2. Apply inline Bold, Italic, and Link spans
            for (span in doc.spans) {
                val s = span.start.coerceIn(0, raw.length)
                val e = span.end.coerceIn(0, raw.length)
                if (e > s) {
                    if (span.bold) {
                        addStyle(SpanStyle(fontWeight = FontWeight.Bold), s, e)
                    }
                    if (span.italic) {
                        addStyle(SpanStyle(fontStyle = FontStyle.Italic), s, e)
                    }
                    if (!span.link.isNullOrBlank()) {
                        addStyle(
                            SpanStyle(
                                color = accentColor,
                                textDecoration = TextDecoration.Underline
                            ),
                            s,
                            e
                        )
                    }
                }
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}

// ============================================================================
// DATE FORMATTING HELPER
// ============================================================================

fun formatRelativeDate(millis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - millis
    val calNow = Calendar.getInstance()
    val calNote = Calendar.getInstance().apply { timeInMillis = millis }

    return when {
        diff < 60_000L -> "Just now"
        calNow.get(Calendar.YEAR) == calNote.get(Calendar.YEAR) &&
            calNow.get(Calendar.DAY_OF_YEAR) == calNote.get(Calendar.DAY_OF_YEAR) -> {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
        }
        calNow.get(Calendar.YEAR) == calNote.get(Calendar.YEAR) &&
            calNow.get(Calendar.DAY_OF_YEAR) - calNote.get(Calendar.DAY_OF_YEAR) == 1 -> {
            "Yesterday"
        }
        calNow.get(Calendar.YEAR) == calNote.get(Calendar.YEAR) -> {
            SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(millis))
        }
        else -> {
            SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(millis))
        }
    }
}

// ============================================================================
// MAIN ACTIVITY & THEME
// ============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val db = NotesDatabase(applicationContext)
        val prefs = getSharedPreferences("notes_settings", Context.MODE_PRIVATE)

        setContent {
            var isDarkTheme by remember { mutableStateOf(prefs.getBoolean("dark_mode", false)) }
            var fontScaleIndex by remember { mutableStateOf(prefs.getInt("font_scale", 1)) } // 0=Small, 1=Default, 2=Large

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

// ============================================================================
// ROOT APP COORDINATOR
// ============================================================================

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

    // Modal states
    var noteToRename by remember { mutableStateOf<Note?>(null) }
    var noteToDelete by remember { mutableStateOf<Note?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    fun refreshNotes() {
        notes = db.getAllNotes()
    }

    fun handleDeleteWithUndo(target: Note) {
        db.deleteNote(target.id)
        if (activeNoteId == target.id) {
            activeNoteId = null
        }
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
                    onRequestRename = { current ->
                        noteToRename = current
                    },
                    onRequestDelete = { current ->
                        noteToDelete = current
                    }
                )
            } else {
                NotesHomeScreen(
                    notes = notes,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    onOpenNote = { note -> activeNoteId = note.id },
                    onCreateNote = {
                        val fresh = Note(
                            title = "",
                            document = RichNoteDocument()
                        )
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

    // Rename Dialog
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

    // Delete Confirmation Dialog
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

    // Genuine Settings Dialog
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

// ============================================================================
// HOMEPAGE (FOCUSED ON NOTES)
// ============================================================================

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
                note.title.lowercase().contains(q) ||
                    note.document.text.lowercase().contains(q)
            }
        }
        matching.sortedByDescending { it.updatedAt }
    }

    val pinnedNotes = remember(filteredNotes) {
        filteredNotes.filter { it.isPinned }
    }

    val unpinnedNotes = remember(filteredNotes) {
        filteredNotes.filter { !it.isPinned }
    }

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

            // Clean App Header
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

            // Search Field
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

            // Notes List or Empty State
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
                            text = if (searchQuery.isNotBlank()) {
                                "Try a different search term."
                            } else {
                                "Create a note to start writing."
                            },
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
                    // Pinned Section (only shown when pinned notes exist)
                    if (pinnedNotes.isNotEmpty()) {
                        item(key = "header_pinned") {
                            SectionHeader("Pinned")
                        }
                        items(pinnedNotes, key = { "pinned_${it.id}" }) { note ->
                            NoteListItem(
                                note = note,
                                onClick = { onOpenNote(note) },
                                onTogglePin = { onTogglePin(note) },
                                onRename = { onRequestRename(note) },
                                onDelete = { onRequestDelete(note) }
                            )
                        }
                        item(key = "spacer_pinned") {
                            Spacer(Modifier.height(20.dp))
                        }
                    }

                    // All Notes Section
                    item(key = "header_all_notes") {
                        SectionHeader("All Notes")
                    }
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

                val preview = note.previewText
                Text(
                    text = preview.ifBlank { "No additional text" },
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

            // Visible Three-Dot Action Menu
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

// ============================================================================
// NOTE EDITOR SCREEN (DISTRACTION-FREE WYSIWYG CANVAS)
// ============================================================================

enum class SaveStatus {
    SAVED,
    SAVING
}

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
    // Local editor states keyed by note.id so background autosave never interrupts typing
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

    // Sync external rename if triggered via the Rename dialog
    LaunchedEffect(note.title) {
        if (note.title != title) {
            title = note.title
        }
    }

    var saveStatus by remember { mutableStateOf(SaveStatus.SAVED) }
    var showFormattingBar by remember { mutableStateOf(false) }
    var activeBold by remember { mutableStateOf(false) }
    var activeItalic by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }

    val bodyFocusRequester = remember { FocusRequester() }
    val titleFocusRequester = remember { FocusRequester() }

    // Auto-focus title when creating a brand new empty note
    LaunchedEffect(note.id) {
        if (note.title.isEmpty() && note.document.text.isEmpty()) {
            delay(100)
            runCatching { titleFocusRequester.requestFocus() }
        }
    }

    fun buildCurrentNote(): Note {
        return note.copy(
            title = title.trim(),
            document = doc,
            updatedAt = System.currentTimeMillis()
        )
    }

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

    // Settle Saving -> Saved indicator smoothly
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

    // Determine current line index and line bounds for toolbar controls
    val currentCursor = textFieldValue.selection.start.coerceIn(0, textFieldValue.text.length)
    val currentLineIndex = remember(textFieldValue.text, currentCursor) {
        textFieldValue.text.substring(0, currentCursor).count { it == '\n' }
    }
    val currentHeadingStyle = doc.lineHeadings[currentLineIndex] ?: HeadingStyle.TEXT

    // Sync activeBold / activeItalic with current cursor or selection
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

    // Show formatting bar automatically when text is selected, or when toggled via 'Aa'
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
