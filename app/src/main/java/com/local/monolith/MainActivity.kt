@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class
)

package com.local.monolith

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ============================================================================
// DATA MODELS: TEXT, INLINE STYLES & VECTOR SKETCH STROKES
// ============================================================================

enum class HeadingStyle(val label: String) {
    TEXT("Text"),
    SMALL("Small heading"),
    LARGE("Large heading")
}

enum class DrawingTool(val label: String, val alpha: Float) {
    PEN("Pen", 1.0f),
    MARKER("Marker", 0.85f),
    HIGHLIGHTER("Highlighter", 0.34f),
    ERASER("Eraser", 1.0f)
}

data class DrawPoint(
    val xDp: Float,
    val yDp: Float
)

data class DrawStroke(
    val tool: DrawingTool,
    val colorIndex: Int,
    val widthDp: Float,
    val points: List<DrawPoint>
)

val InkPaletteSwatches = listOf(
    Color(0xFF1F1F1D), // 0: Adaptive Ink (Charcoal on Light, Cream on Dark)
    Color(0xFF2563EB), // 1: Cobalt Blue
    Color(0xFFDC2626), // 2: Crimson Red
    Color(0xFF16A34A), // 3: Forest Green
    Color(0xFFD97706), // 4: Warm Amber
    Color(0xFF7C3AED)  // 5: Royal Violet
)

val SizePresetLabels = listOf("XS", "S", "M", "L", "XL")

fun resolveInkColor(colorIndex: Int, adaptiveDefault: Color): Color {
    return if (colorIndex <= 0 || colorIndex >= InkPaletteSwatches.size) {
        adaptiveDefault
    } else {
        InkPaletteSwatches[colorIndex]
    }
}

// 5 calibrated sizes (0=XS, 1=S, 2=M, 3=L, 4=XL) for Pens and Precision Eraser
fun strokeWidthForTool(tool: DrawingTool, sizeIndex: Int): Float {
    val idx = sizeIndex.coerceIn(0, 4)
    return when (tool) {
        DrawingTool.PEN -> when (idx) {
            0 -> 1.4f
            1 -> 2.4f
            2 -> 4.0f
            3 -> 6.5f
            else -> 10.0f
        }
        DrawingTool.MARKER -> when (idx) {
            0 -> 3.5f
            1 -> 5.5f
            2 -> 8.5f
            3 -> 13.0f
            else -> 18.0f
        }
        DrawingTool.HIGHLIGHTER -> when (idx) {
            0 -> 10.0f
            1 -> 14.0f
            2 -> 20.0f
            3 -> 26.0f
            else -> 34.0f
        }
        DrawingTool.ERASER -> when (idx) {
            0 -> 5.0f   // XS: 2.5dp radius precision tip
            1 -> 10.0f  // S:  5.0dp radius
            2 -> 18.0f  // M:  9.0dp radius
            3 -> 30.0f  // L:  15.0dp radius
            else -> 48.0f // XL: 24.0dp radius
        }
    }
}

// Interpolate points along a drag segment so fast strokes and fast eraser swipes never skip
fun interpolatePoints(from: DrawPoint, to: DrawPoint, stepDp: Float = 1.5f): List<DrawPoint> {
    val dist = hypot(to.xDp - from.xDp, to.yDp - from.yDp)
    if (dist <= stepDp) return listOf(to)
    val steps = ceil(dist / stepDp).toInt().coerceIn(1, 120)
    val list = ArrayList<DrawPoint>(steps)
    for (i in 1..steps) {
        val t = i.toFloat() / steps.toFloat()
        list.add(
            DrawPoint(
                xDp = from.xDp + (to.xDp - from.xDp) * t,
                yDp = from.yDp + (to.yDp - from.yDp) * t
            )
        )
    }
    return list
}

// Distance from point P to segment AB in dp
fun distancePointToSegmentDp(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val abx = bx - ax
    val aby = by - ay
    val lenSq = abx * abx + aby * aby
    if (lenSq <= 0.0001f) return hypot(px - ax, py - ay)
    val t = (((px - ax) * abx + (py - ay) * aby) / lenSq).coerceIn(0f, 1f)
    val projX = ax + t * abx
    val projY = ay + t * aby
    return hypot(px - projX, py - projY)
}

// Continuous swipe-aware eraser that splits strokes cleanly wherever the eraser passes
fun eraseStrokesAlongSegment(
    strokes: List<DrawStroke>,
    fromPt: DrawPoint,
    toPt: DrawPoint,
    eraserRadiusDp: Float
): List<DrawStroke> {
    if (strokes.isEmpty()) return strokes
    val result = mutableListOf<DrawStroke>()
    var anyChanged = false

    for (stroke in strokes) {
        val threshold = eraserRadiusDp + (stroke.widthDp * 0.45f)
        // Densify stroke points if two vertices are far apart so long straight lines erase cleanly in the middle
        val densePoints = mutableListOf<DrawPoint>()
        stroke.points.forEach { pt ->
            val prev = densePoints.lastOrNull()
            if (prev == null) {
                densePoints.add(pt)
            } else {
                densePoints.addAll(interpolatePoints(prev, pt, stepDp = 2.0f))
            }
        }

        val hitAny = densePoints.any { pt ->
            distancePointToSegmentDp(pt.xDp, pt.yDp, fromPt.xDp, fromPt.yDp, toPt.xDp, toPt.yDp) <= threshold
        }

        if (!hitAny) {
            result.add(stroke)
        } else {
            anyChanged = true
            var currentSegment = mutableListOf<DrawPoint>()
            for (pt in densePoints) {
                val dist = distancePointToSegmentDp(pt.xDp, pt.yDp, fromPt.xDp, fromPt.yDp, toPt.xDp, toPt.yDp)
                if (dist > threshold) {
                    currentSegment.add(pt)
                } else {
                    if (currentSegment.isNotEmpty()) {
                        result.add(stroke.copy(points = currentSegment))
                        currentSegment = mutableListOf()
                    }
                }
            }
            if (currentSegment.isNotEmpty()) {
                result.add(stroke.copy(points = currentSegment))
            }
        }
    }

    return if (anyChanged) result else strokes
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
    val lineHeadings: Map<Int, HeadingStyle> = emptyMap(),
    val strokes: List<DrawStroke> = emptyList(),
    val canvasHeightDp: Int = 300
) {
    fun toPlainPreview(): String {
        val textPreview = text.lines()
            .map { it.removePrefix("• ").removePrefix("☐ ").removePrefix("☑ ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        return when {
            textPreview.isNotEmpty() && strokes.isNotEmpty() -> "$textPreview  ·  Sketch"
            textPreview.isNotEmpty() -> textPreview
            strokes.isNotEmpty() -> "Handwritten sketch"
            else -> ""
        }
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

        val strokesArray = JSONArray()
        strokes.filter { it.points.isNotEmpty() }.forEach { stroke ->
            val ptsArray = JSONArray()
            stroke.points.forEach { pt ->
                ptsArray.put(((pt.xDp * 10f).roundToInt() / 10f).toDouble())
                ptsArray.put(((pt.yDp * 10f).roundToInt() / 10f).toDouble())
            }
            strokesArray.put(JSONObject().apply {
                put("t", stroke.tool.name)
                put("c", stroke.colorIndex)
                put("w", stroke.widthDp.toDouble())
                put("p", ptsArray)
            })
        }

        return JSONObject().apply {
            put("_format", "rich_v2")
            put("text", text)
            put("spans", spansArray)
            put("headings", headingsObj)
            put("strokes", strokesArray)
            put("canvasH", canvasHeightDp)
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

                        val strokes = mutableListOf<DrawStroke>()
                        val strokesArray = obj.optJSONArray("strokes")
                        if (strokesArray != null) {
                            for (i in 0 until strokesArray.length()) {
                                val stObj = strokesArray.getJSONObject(i)
                                val tool = runCatching { DrawingTool.valueOf(stObj.optString("t", "PEN")) }
                                    .getOrDefault(DrawingTool.PEN)
                                val colorIdx = stObj.optInt("c", 0)
                                val widthDp = stObj.optDouble("w", 4.0).toFloat()
                                val ptsArr = stObj.optJSONArray("p")
                                val pts = mutableListOf<DrawPoint>()
                                if (ptsArr != null) {
                                    var j = 0
                                    while (j + 1 < ptsArr.length()) {
                                        pts.add(
                                            DrawPoint(
                                                xDp = ptsArr.optDouble(j, 0.0).toFloat(),
                                                yDp = ptsArr.optDouble(j + 1, 0.0).toFloat()
                                            )
                                        )
                                        j += 2
                                    }
                                }
                                if (pts.isNotEmpty()) {
                                    strokes.add(DrawStroke(tool, colorIdx, widthDp, pts))
                                }
                            }
                        }

                        val canvasH = obj.optInt("canvasH", 300).coerceIn(200, 2600)
                        return RichNoteDocument(
                            text = text,
                            spans = spans,
                            lineHeadings = headings,
                            strokes = strokes,
                            canvasHeightDp = canvasH
                        )
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
    val folder: String = "", // Hierarchical path, e.g. "" (Root), "Work", "Work/Projects"
    val isPinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val previewText: String get() = document.toPlainPreview()
}

// ============================================================================
// HIERARCHICAL FOLDER HELPERS
// ============================================================================

fun normalizeFolderPath(path: String): String {
    return path.split("/")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("/")
}

fun parentFolderPath(path: String): String {
    val normalized = normalizeFolderPath(path)
    val lastSlash = normalized.lastIndexOf('/')
    return if (lastSlash == -1) "" else normalized.substring(0, lastSlash)
}

fun folderDisplayName(path: String): String {
    val normalized = normalizeFolderPath(path)
    val lastSlash = normalized.lastIndexOf('/')
    return if (lastSlash == -1) normalized else normalized.substring(lastSlash + 1)
}

// Ensure every parent in "A/B/C" exists in the folder list ("A", "A/B", "A/B/C")
fun expandAllFolderPaths(rawPaths: Collection<String>): List<String> {
    val set = linkedSetOf<String>()
    for (raw in rawPaths) {
        val parts = raw.split("/").map { it.trim() }.filter { it.isNotEmpty() }
        var current = ""
        for (part in parts) {
            current = if (current.isEmpty()) part else "$current/$part"
            set.add(current)
        }
    }
    return set.toList().sortedBy { it.lowercase() }
}

// Direct subfolders of `parentPath`
fun directSubfoldersOf(parentPath: String, allFolders: List<String>): List<String> {
    val normParent = normalizeFolderPath(parentPath)
    return allFolders.filter { folder ->
        parentFolderPath(folder).equals(normParent, ignoreCase = true) &&
            !folder.equals(normParent, ignoreCase = true)
    }.sortedBy { folderDisplayName(it).lowercase() }
}

// ============================================================================
// VOLATILE IN-MEMORY UNDO/REDO SNAPSHOTS (CLEARED AUTOMATICALLY ON APP CLOSE)
// ============================================================================

data class EditorSnapshot(
    val title: String,
    val document: RichNoteDocument,
    val selection: TextRange,
    val timestamp: Long = System.currentTimeMillis()
)

class SessionUndoQueue(initialSnapshot: EditorSnapshot) {
    private val maxSteps = 30
    val undoStack = mutableStateListOf(initialSnapshot)
    val redoStack = mutableStateListOf<EditorSnapshot>()

    val canUndo: Boolean get() = undoStack.size > 1
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun recordChange(snapshot: EditorSnapshot, forceNewStep: Boolean = false) {
        val current = undoStack.lastOrNull()
        if (current != null && current.title == snapshot.title && current.document == snapshot.document) {
            return
        }
        redoStack.clear()

        if (!forceNewStep && current != null && undoStack.size > 1) {
            val timeDiff = snapshot.timestamp - current.timestamp
            val charDiff = abs(snapshot.document.text.length - current.document.text.length)
            val sameLineCount = snapshot.document.text.count { it == '\n' } == current.document.text.count { it == '\n' }
            val sameStrokes = snapshot.document.strokes.size == current.document.strokes.size
            if (timeDiff < 1100L && charDiff <= 12 && sameLineCount && sameStrokes &&
                current.document.lineHeadings == snapshot.document.lineHeadings
            ) {
                undoStack[undoStack.lastIndex] = snapshot
                return
            }
        }

        undoStack.add(snapshot)
        while (undoStack.size > maxSteps) {
            undoStack.removeAt(0)
        }
    }

    fun undo(): EditorSnapshot? {
        if (!canUndo) return null
        val popped = undoStack.removeAt(undoStack.lastIndex)
        redoStack.add(popped)
        return undoStack.last()
    }

    fun redo(): EditorSnapshot? {
        if (!canRedo) return null
        val restored = redoStack.removeAt(redoStack.lastIndex)
        undoStack.add(restored)
        return restored
    }
}

// ============================================================================
// LOCAL SQLITE DATABASE
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
            db.execSQL("UPDATE notes SET folder = '' WHERE folder = 'Notes'")
        } catch (_: Exception) {}
    }

    fun upsertNote(note: Note) {
        try {
            val values = ContentValues().apply {
                put("id", note.id)
                put("title", note.title)
                put("content", note.document.serialize())
                put("folder", normalizeFolderPath(note.folder))
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

    fun renameFolderTree(oldFolderPath: String, newFolderPath: String) {
        val normOld = normalizeFolderPath(oldFolderPath)
        val normNew = normalizeFolderPath(newFolderPath)
        if (normOld.isEmpty() || normNew.isEmpty()) return
        val all = getAllNotes()
        all.forEach { note ->
            val nf = normalizeFolderPath(note.folder)
            if (nf.equals(normOld, ignoreCase = true)) {
                upsertNote(note.copy(folder = normNew))
            } else if (nf.startsWith("$normOld/", ignoreCase = true)) {
                val suffix = nf.substring(normOld.length)
                upsertNote(note.copy(folder = normNew + suffix))
            }
        }
    }

    fun removeFolderTreeMoveNotesToParent(targetFolderPath: String) {
        val normTarget = normalizeFolderPath(targetFolderPath)
        val parent = parentFolderPath(normTarget)
        val all = getAllNotes()
        all.forEach { note ->
            val nf = normalizeFolderPath(note.folder)
            if (nf.equals(normTarget, ignoreCase = true) || nf.startsWith("$normTarget/", ignoreCase = true)) {
                upsertNote(note.copy(folder = parent))
            }
        }
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
                    val rawFolder = c.getString(c.getColumnIndexOrThrow("folder")) ?: ""
                    val isPinned = c.getInt(c.getColumnIndexOrThrow("is_pinned")) == 1
                    val createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
                    val updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
                    list.add(
                        Note(
                            id = id,
                            title = if (title == "Untitled Note") "" else title,
                            document = RichNoteDocument.deserialize(rawContent),
                            folder = if (rawFolder == "Notes") "" else normalizeFolderPath(rawFolder),
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

fun loadSavedFolders(prefs: SharedPreferences): List<String> {
    val raw = prefs.getString("custom_folders_json", null) ?: return emptyList()
    return try {
        val arr = JSONArray(raw)
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val name = normalizeFolderPath(arr.optString(i))
            if (name.isNotEmpty()) list.add(name)
        }
        expandAllFolderPaths(list)
    } catch (_: Exception) {
        emptyList()
    }
}

fun saveFoldersList(prefs: SharedPreferences, folders: List<String>) {
    val arr = JSONArray()
    expandAllFolderPaths(folders).forEach { arr.put(it) }
    prefs.edit().putString("custom_folders_json", arr.toString()).apply()
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

    return oldDoc.copy(
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
                        prefs = prefs,
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
    prefs: SharedPreferences,
    isDarkTheme: Boolean,
    onToggleDarkTheme: (Boolean) -> Unit,
    fontScaleIndex: Int,
    onSelectFontScale: (Int) -> Unit,
    baseFontSizeSp: Float
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var notes by remember { mutableStateOf(db.getAllNotes()) }
    var customFolders by remember { mutableStateOf(loadSavedFolders(prefs)) }
    // Current directory path: "" = Home (Root), "Work" = inside Work, "Work/2026" = inside 2026
    var currentFolderPath by remember { mutableStateOf("") }
    var activeNoteId by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    // Session-only Undo/Redo history map (lives strictly in RAM; cleared when app closes)
    val sessionUndoMap = remember { mutableMapOf<String, SessionUndoQueue>() }

    // Modal states
    var noteToRename by remember { mutableStateOf<Note?>(null) }
    var noteToDelete by remember { mutableStateOf<Note?>(null) }
    var noteToMoveFolder by remember { mutableStateOf<Note?>(null) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<String?>(null) }
    var folderToDelete by remember { mutableStateOf<String?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    val allFolders = remember(customFolders, notes) {
        val fromNotes = notes.map { normalizeFolderPath(it.folder) }.filter { it.isNotEmpty() }
        expandAllFolderPaths(customFolders + fromNotes)
    }

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
                val undoQueue = remember(activeNote.id) {
                    sessionUndoMap.getOrPut(activeNote.id) {
                        SessionUndoQueue(
                            EditorSnapshot(
                                title = activeNote.title,
                                document = activeNote.document,
                                selection = TextRange(activeNote.document.text.length)
                            )
                        )
                    }
                }

                NoteEditorScreen(
                    note = activeNote,
                    undoQueue = undoQueue,
                    baseFontSizeSp = baseFontSizeSp,
                    hasFolders = allFolders.isNotEmpty(),
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
                    onRequestMoveFolder = { current -> noteToMoveFolder = current },
                    onRequestDelete = { current -> noteToDelete = current }
                )
            } else {
                NotesHomeScreen(
                    notes = notes,
                    allFolders = allFolders,
                    currentFolderPath = currentFolderPath,
                    onNavigateFolder = { targetPath ->
                        currentFolderPath = normalizeFolderPath(targetPath)
                    },
                    onRequestRenameFolder = { folderPath -> folderToRename = folderPath },
                    onRequestDeleteFolder = { folderPath -> folderToDelete = folderPath },
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    onOpenNote = { note -> activeNoteId = note.id },
                    onCreateNote = {
                        val fresh = Note(
                            title = "",
                            document = RichNoteDocument(),
                            folder = currentFolderPath
                        )
                        db.upsertNote(fresh)
                        refreshNotes()
                        activeNoteId = fresh.id
                    },
                    onRequestCreateFolder = { showNewFolderDialog = true },
                    onTogglePin = { note ->
                        val updated = note.copy(
                            isPinned = !note.isPinned,
                            updatedAt = System.currentTimeMillis()
                        )
                        db.upsertNote(updated)
                        refreshNotes()
                    },
                    onRequestRename = { note -> noteToRename = note },
                    onRequestMoveFolder = { note -> noteToMoveFolder = note },
                    onRequestDelete = { note -> noteToDelete = note },
                    onOpenSettings = { showSettingsDialog = true }
                )
            }
        }
    }

    if (showNewFolderDialog) {
        NewFolderDialog(
            parentFolderPath = currentFolderPath,
            onDismiss = { showNewFolderDialog = false },
            onConfirm = { rawName ->
                val cleanName = rawName.trim().replace("/", "-")
                if (cleanName.isNotEmpty()) {
                    val fullPath = if (currentFolderPath.isEmpty()) cleanName else "$currentFolderPath/$cleanName"
                    val updated = expandAllFolderPaths(customFolders + fullPath)
                    customFolders = updated
                    saveFoldersList(prefs, updated)
                }
                showNewFolderDialog = false
            }
        )
    }

    if (folderToRename != null) {
        val targetFolder = folderToRename!!
        RenameFolderDialog(
            currentName = folderDisplayName(targetFolder),
            onDismiss = { folderToRename = null },
            onConfirm = { rawNewName ->
                val cleanNewName = rawNewName.trim().replace("/", "-")
                if (cleanNewName.isNotEmpty()) {
                    val parent = parentFolderPath(targetFolder)
                    val newPath = if (parent.isEmpty()) cleanNewName else "$parent/$cleanNewName"
                    val updatedFolders = customFolders.map { existing ->
                        if (existing.equals(targetFolder, ignoreCase = true)) {
                            newPath
                        } else if (existing.startsWith("$targetFolder/", ignoreCase = true)) {
                            newPath + existing.substring(targetFolder.length)
                        } else {
                            existing
                        }
                    }
                    customFolders = expandAllFolderPaths(updatedFolders + newPath)
                    saveFoldersList(prefs, customFolders)
                    db.renameFolderTree(targetFolder, newPath)
                    if (currentFolderPath.equals(targetFolder, ignoreCase = true)) {
                        currentFolderPath = newPath
                    } else if (currentFolderPath.startsWith("$targetFolder/", ignoreCase = true)) {
                        currentFolderPath = newPath + currentFolderPath.substring(targetFolder.length)
                    }
                    refreshNotes()
                }
                folderToRename = null
            }
        )
    }

    if (folderToDelete != null) {
        val targetFolder = folderToDelete!!
        DeleteFolderDialog(
            folderName = folderDisplayName(targetFolder),
            onDismiss = { folderToDelete = null },
            onConfirmDelete = {
                val updated = customFolders.filterNot {
                    it.equals(targetFolder, ignoreCase = true) ||
                        it.startsWith("$targetFolder/", ignoreCase = true)
                }
                customFolders = updated
                saveFoldersList(prefs, updated)
                db.removeFolderTreeMoveNotesToParent(targetFolder)
                if (currentFolderPath.equals(targetFolder, ignoreCase = true) ||
                    currentFolderPath.startsWith("$targetFolder/", ignoreCase = true)
                ) {
                    currentFolderPath = parentFolderPath(targetFolder)
                }
                refreshNotes()
                folderToDelete = null
            }
        )
    }

    if (noteToMoveFolder != null) {
        val target = noteToMoveFolder!!
        MoveToFolderDialog(
            currentFolder = target.folder,
            folders = allFolders,
            onDismiss = { noteToMoveFolder = null },
            onSelectFolder = { chosenFolder ->
                val updated = target.copy(
                    folder = normalizeFolderPath(chosenFolder),
                    updatedAt = System.currentTimeMillis()
                )
                db.upsertNote(updated)
                refreshNotes()
                noteToMoveFolder = null
            },
            onCreateNewFolder = {
                noteToMoveFolder = null
                showNewFolderDialog = true
            }
        )
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

// ============================================================================
// HOMEPAGE WITH TRADITIONAL HIERARCHICAL FOLDERS
// ============================================================================

@Composable
fun NotesHomeScreen(
    notes: List<Note>,
    allFolders: List<String>,
    currentFolderPath: String,
    onNavigateFolder: (String) -> Unit,
    onRequestRenameFolder: (String) -> Unit,
    onRequestDeleteFolder: (String) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onOpenNote: (Note) -> Unit,
    onCreateNote: () -> Unit,
    onRequestCreateFolder: () -> Unit,
    onTogglePin: (Note) -> Unit,
    onRequestRename: (Note) -> Unit,
    onRequestMoveFolder: (Note) -> Unit,
    onRequestDelete: (Note) -> Unit,
    onOpenSettings: () -> Unit
) {
    // System back button steps up one folder level when inside a folder
    BackHandler(enabled = currentFolderPath.isNotEmpty()) {
        onNavigateFolder(parentFolderPath(currentFolderPath))
    }

    val isSearching = searchQuery.trim().isNotEmpty()

    // Direct child folders of the current folder (or matching folders during search)
    val visibleSubfolders = remember(allFolders, currentFolderPath, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) {
            directSubfoldersOf(currentFolderPath, allFolders)
        } else {
            allFolders.filter { folder ->
                val inScope = currentFolderPath.isEmpty() ||
                    folder.startsWith("$currentFolderPath/", ignoreCase = true)
                inScope && folderDisplayName(folder).lowercase().contains(q)
            }
        }
    }

    // Notes in the current folder (or matching notes during search)
    val visibleNotes = remember(notes, currentFolderPath, searchQuery) {
        val q = searchQuery.trim().lowercase()
        notes.filter { note ->
            if (q.isEmpty()) {
                note.folder.equals(currentFolderPath, ignoreCase = true)
            } else {
                val inScope = currentFolderPath.isEmpty() ||
                    note.folder.equals(currentFolderPath, ignoreCase = true) ||
                    note.folder.startsWith("$currentFolderPath/", ignoreCase = true)
                val matchesText = note.title.lowercase().contains(q) ||
                    note.document.text.lowercase().contains(q)
                inScope && matchesText
            }
        }.sortedByDescending { it.updatedAt }
    }

    val pinnedNotes = remember(visibleNotes) { visibleNotes.filter { it.isPinned } }
    val unpinnedNotes = remember(visibleNotes) { visibleNotes.filter { !it.isPinned } }

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

            // App Header: Shows "Folio" at Root, or Back Arrow + Folder Title when inside a folder
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (currentFolderPath.isNotEmpty()) {
                        IconButton(
                            onClick = { onNavigateFolder(parentFolderPath(currentFolderPath)) },
                            modifier = Modifier
                                .size(36.dp)
                                .padding(end = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Up one folder",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                    Text(
                        text = if (currentFolderPath.isEmpty()) "Folio" else folderDisplayName(currentFolderPath),
                        fontSize = if (currentFolderPath.isEmpty()) 30.sp else 25.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.5).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onRequestCreateFolder,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CreateNewFolder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "New folder",
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

            // Clickable Breadcrumb Trail when inside a folder (e.g., Folio › Work › Projects)
            if (currentFolderPath.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                FolderBreadcrumbBar(
                    currentFolderPath = currentFolderPath,
                    onNavigateFolder = onNavigateFolder
                )
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
                                text = if (currentFolderPath.isEmpty()) "Search notes and folders"
                                else "Search in ${folderDisplayName(currentFolderPath)}",
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

            if (visibleSubfolders.isEmpty() && visibleNotes.isEmpty()) {
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
                            text = when {
                                isSearching -> "No matches found"
                                currentFolderPath.isNotEmpty() -> "This folder is empty"
                                else -> "No notes or folders yet"
                            },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (isSearching) {
                                "Try a different search term."
                            } else {
                                "Tap New folder above or the + button below to create a note here."
                            },
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    // 1. Traditional Folders Section
                    if (visibleSubfolders.isNotEmpty()) {
                        item(key = "header_folders") {
                            SectionHeader("Folders")
                        }
                        items(visibleSubfolders, key = { "folder_$it" }) { folderPath ->
                            val subfolderCount = remember(allFolders, folderPath) {
                                directSubfoldersOf(folderPath, allFolders).size
                            }
                            val notesInFolderCount = remember(notes, folderPath) {
                                notes.count { it.folder.equals(folderPath, ignoreCase = true) }
                            }
                            FolderListItem(
                                folderPath = folderPath,
                                subfolderCount = subfolderCount,
                                noteCount = notesInFolderCount,
                                onOpen = {
                                    onSearchQueryChange("")
                                    onNavigateFolder(folderPath)
                                },
                                onRename = { onRequestRenameFolder(folderPath) },
                                onDelete = { onRequestDeleteFolder(folderPath) }
                            )
                        }
                        item(key = "spacer_folders") {
                            Spacer(Modifier.height(20.dp))
                        }
                    }

                    // 2. Pinned Notes in this folder
                    if (pinnedNotes.isNotEmpty()) {
                        item(key = "header_pinned") { SectionHeader("Pinned") }
                        items(pinnedNotes, key = { "pinned_${it.id}" }) { note ->
                            NoteListItem(
                                note = note,
                                showFolderBadge = isSearching && note.folder.isNotBlank(),
                                onClick = { onOpenNote(note) },
                                onTogglePin = { onTogglePin(note) },
                                onRename = { onRequestRename(note) },
                                onMoveFolder = { onRequestMoveFolder(note) },
                                onDelete = { onRequestDelete(note) }
                            )
                        }
                        item(key = "spacer_pinned") { Spacer(Modifier.height(20.dp)) }
                    }

                    // 3. Notes in this folder
                    if (unpinnedNotes.isNotEmpty() || (visibleSubfolders.isEmpty() && pinnedNotes.isNotEmpty())) {
                        item(key = "header_notes") {
                            SectionHeader(if (currentFolderPath.isEmpty()) "Notes" else "Notes in ${folderDisplayName(currentFolderPath)}")
                        }
                        if (unpinnedNotes.isEmpty() && pinnedNotes.isNotEmpty()) {
                            item(key = "all_pinned_hint") {
                                Text(
                                    text = "All notes in this folder are pinned above.",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp)
                                )
                            }
                        } else {
                            items(unpinnedNotes, key = { "note_${it.id}" }) { note ->
                                NoteListItem(
                                    note = note,
                                    showFolderBadge = isSearching && note.folder.isNotBlank(),
                                    onClick = { onOpenNote(note) },
                                    onTogglePin = { onTogglePin(note) },
                                    onRename = { onRequestRename(note) },
                                    onMoveFolder = { onRequestMoveFolder(note) },
                                    onDelete = { onRequestDelete(note) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom-Right Floating Plus (+) Button to Create a New Note
        FloatingActionButton(
            onClick = onCreateNote,
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 3.dp,
                pressedElevation = 6.dp
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "New note",
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
fun FolderBreadcrumbBar(
    currentFolderPath: String,
    onNavigateFolder: (String) -> Unit
) {
    val segments = remember(currentFolderPath) {
        currentFolderPath.split("/").map { it.trim() }.filter { it.isNotEmpty() }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Folio",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onNavigateFolder("") }
                .padding(horizontal = 4.dp, vertical = 2.dp)
        )

        var accumulated = ""
        segments.forEachIndexed { index, name ->
            accumulated = if (accumulated.isEmpty()) name else "$accumulated/$name"
            val target = accumulated
            val isLast = index == segments.lastIndex

            Text(
                text = "›",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Text(
                text = name,
                fontSize = 13.sp,
                fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Medium,
                color = if (isLast) MaterialTheme.colorScheme.onBackground
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = !isLast) { onNavigateFolder(target) }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
fun FolderListItem(
    folderPath: String,
    subfolderCount: Int,
    noteCount: Int,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    val subtitle = remember(subfolderCount, noteCount) {
        val parts = mutableListOf<String>()
        if (subfolderCount > 0) {
            parts.add(if (subfolderCount == 1) "1 folder" else "$subfolderCount folders")
        }
        if (noteCount > 0) {
            parts.add(if (noteCount == 1) "1 note" else "$noteCount notes")
        }
        if (parts.isEmpty()) "Empty folder" else parts.joinToString("  ·  ")
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = { menuExpanded = true }
                )
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column {
                    Text(
                        text = folderDisplayName(folderPath),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreHoriz,
                            contentDescription = "Folder actions",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        shape = RoundedCornerShape(12.dp),
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename folder") },
                            leadingIcon = {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Delete folder",
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
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            }
                        )
                    }
                }

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Open folder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp)
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
    showFolderBadge: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onMoveFolder: () -> Unit,
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

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatRelativeDate(note.updatedAt),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                    if (showFolderBadge && note.folder.isNotBlank()) {
                        Text(
                            text = "  ·  ${note.folder}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
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
                    onMoveFolder = {
                        menuExpanded = false
                        onMoveFolder()
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
    onMoveFolder: () -> Unit,
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
        DropdownMenuItem(
            text = { Text("Move to folder") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = onMoveFolder
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
// NOTE EDITOR SCREEN (WRITING + FULL-WIDTH EDGE-TO-EDGE SKETCHING)
// ============================================================================

enum class SaveStatus { SAVED, SAVING }

@Composable
fun NoteEditorScreen(
    note: Note,
    undoQueue: SessionUndoQueue,
    baseFontSizeSp: Float,
    hasFolders: Boolean,
    onAutoSave: (Note) -> Unit,
    onBack: () -> Unit,
    onTogglePin: (Note) -> Unit,
    onRequestRename: (Note) -> Unit,
    onRequestMoveFolder: (Note) -> Unit,
    onRequestDelete: (Note) -> Unit
) {
    val focusManager = LocalFocusManager.current

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
    var isDrawingMode by remember { mutableStateOf(false) }
    var activeBold by remember { mutableStateOf(false) }
    var activeItalic by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }

    // Separate size indices for Pens vs Eraser so switching to Eraser remembers your preferred eraser size
    var selectedTool by remember { mutableStateOf(DrawingTool.PEN) }
    var selectedColorIndex by remember { mutableStateOf(0) }
    var penSizeIndex by remember { mutableStateOf(2) }    // Default M
    var eraserSizeIndex by remember { mutableStateOf(1) } // Default S (fine/medium)

    val currentToolSizeIndex = if (selectedTool == DrawingTool.ERASER) eraserSizeIndex else penSizeIndex

    val bodyFocusRequester = remember { FocusRequester() }
    val titleFocusRequester = remember { FocusRequester() }

    LaunchedEffect(note.id) {
        if (note.title.isEmpty() && note.document.text.isEmpty() && note.document.strokes.isEmpty()) {
            delay(100)
            runCatching { titleFocusRequester.requestFocus() }
        }
    }

    fun buildCurrentNote(): Note = note.copy(
        title = title.trim(),
        document = doc,
        updatedAt = System.currentTimeMillis()
    )

    fun triggerImmediateSave(
        updatedDoc: RichNoteDocument = doc,
        updatedTitle: String = title,
        selection: TextRange = textFieldValue.selection,
        recordInUndo: Boolean = true,
        forceNewUndoStep: Boolean = false
    ) {
        if (recordInUndo) {
            undoQueue.recordChange(
                snapshot = EditorSnapshot(
                    title = updatedTitle,
                    document = updatedDoc,
                    selection = selection
                ),
                forceNewStep = forceNewUndoStep
            )
        }
        saveStatus = SaveStatus.SAVING
        onAutoSave(
            note.copy(
                title = updatedTitle.trim(),
                document = updatedDoc,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun applySnapshot(snapshot: EditorSnapshot) {
        title = snapshot.title
        doc = snapshot.document
        val safeEnd = snapshot.selection.end.coerceIn(0, snapshot.document.text.length)
        val safeStart = snapshot.selection.start.coerceIn(0, snapshot.document.text.length)
        textFieldValue = TextFieldValue(
            text = snapshot.document.text,
            selection = TextRange(safeStart, safeEnd)
        )
        triggerImmediateSave(
            updatedDoc = snapshot.document,
            updatedTitle = snapshot.title,
            selection = TextRange(safeStart, safeEnd),
            recordInUndo = false
        )
    }

    LaunchedEffect(title, doc) {
        if (saveStatus == SaveStatus.SAVING) {
            delay(400)
            saveStatus = SaveStatus.SAVED
        }
    }

    BackHandler {
        if (isDrawingMode) {
            isDrawingMode = false
        } else {
            onAutoSave(buildCurrentNote())
            onBack()
        }
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

    val isTextToolbarVisible = !isDrawingMode && (showFormattingBar || !textFieldValue.selection.collapsed)

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
        val newSelection = TextRange(newCursor)

        val updatedDoc = adjustDocumentOnEdit(doc, newText, activeBold = false, activeItalic = false)
        doc = updatedDoc
        textFieldValue = TextFieldValue(text = newText, selection = newSelection)
        triggerImmediateSave(updatedDoc = updatedDoc, selection = newSelection, forceNewUndoStep = true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        // Editor Header Bar
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
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = if (saveStatus == SaveStatus.SAVING) "Saving…" else "Saved",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.padding(end = 6.dp)
                    )

                    IconButton(
                        onClick = { undoQueue.undo()?.let { applySnapshot(it) } },
                        enabled = undoQueue.canUndo
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Undo",
                            tint = if (undoQueue.canUndo) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = { undoQueue.redo()?.let { applySnapshot(it) } },
                        enabled = undoQueue.canRedo
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Redo,
                            contentDescription = "Redo",
                            tint = if (undoQueue.canRedo) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            isDrawingMode = !isDrawingMode
                            if (isDrawingMode) {
                                showFormattingBar = false
                                focusManager.clearFocus()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Brush,
                            contentDescription = "Draw or sketch",
                            tint = if (isDrawingMode) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            if (isDrawingMode) isDrawingMode = false
                            showFormattingBar = !showFormattingBar
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.FormatSize,
                            contentDescription = "Format text",
                            tint = if (isTextToolbarVisible) MaterialTheme.colorScheme.primary
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
                            onMoveFolder = {
                                showMoreMenu = false
                                onRequestMoveFolder(buildCurrentNote())
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

        // Main Writing & Edge-to-Edge Sketching Scroll Area
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState(), enabled = !isDrawingMode),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Section: Folder indicator + Note Title (with comfortable reading margins)
            Column(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                if (note.folder.isNotBlank() || hasFolders) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onRequestMoveFolder(buildCurrentNote()) }
                            .padding(vertical = 4.dp, horizontal = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = note.folder.ifBlank { "Move to folder" },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }

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
            }

            // Full-Bleed Edge-to-Edge Sketch Canvas (Zero Left/Right Margins or Box Borders!)
            if (isDrawingMode || doc.strokes.isNotEmpty()) {
                EdgeToEdgeSketchCanvas(
                    strokes = doc.strokes,
                    canvasHeightDp = doc.canvasHeightDp,
                    isDrawingMode = isDrawingMode,
                    selectedTool = selectedTool,
                    selectedColorIndex = selectedColorIndex,
                    selectedSizeIndex = currentToolSizeIndex,
                    onActivateDrawingMode = {
                        isDrawingMode = true
                        showFormattingBar = false
                        focusManager.clearFocus()
                    },
                    onCommitStrokes = { updatedStrokes ->
                        val updatedDoc = doc.copy(strokes = updatedStrokes)
                        doc = updatedDoc
                        triggerImmediateSave(updatedDoc = updatedDoc, forceNewUndoStep = true)
                    },
                    onExpandCanvas = {
                        val updatedDoc = doc.copy(canvasHeightDp = (doc.canvasHeightDp + 200).coerceAtMost(2600))
                        doc = updatedDoc
                        triggerImmediateSave(updatedDoc = updatedDoc, recordInUndo = false)
                    }
                )
            }

            // Bottom Section: Body WYSIWYG Text Editor
            Column(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 360.dp)
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
                            if (isDrawingMode) isDrawingMode = false
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
                                    val newSel = TextRange((lineStart + 2).coerceAtMost(updatedText.length))
                                    doc = updatedDoc
                                    textFieldValue = TextFieldValue(text = updatedText, selection = newSel)
                                    triggerImmediateSave(updatedDoc = updatedDoc, selection = newSel, forceNewUndoStep = true)
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
                                            val newSel = TextRange(prevLineStart)
                                            doc = updatedDoc
                                            textFieldValue = TextFieldValue(text = cleanedText, selection = newSel)
                                            triggerImmediateSave(updatedDoc = updatedDoc, selection = newSel, forceNewUndoStep = true)
                                            return@BasicTextField
                                        }
                                        prevLine.startsWith("• ") -> {
                                            val autoText = newText.substring(0, insIdx + 1) + "• " + newText.substring(insIdx + 1)
                                            val updatedDoc = adjustDocumentOnEdit(doc, autoText, activeBold, activeItalic)
                                            val newSel = TextRange(insIdx + 3)
                                            doc = updatedDoc
                                            textFieldValue = TextFieldValue(text = autoText, selection = newSel)
                                            triggerImmediateSave(updatedDoc = updatedDoc, selection = newSel, forceNewUndoStep = true)
                                            return@BasicTextField
                                        }
                                        prevLine.startsWith("☐ ") || prevLine.startsWith("☑ ") -> {
                                            val autoText = newText.substring(0, insIdx + 1) + "☐ " + newText.substring(insIdx + 1)
                                            val updatedDoc = adjustDocumentOnEdit(doc, autoText, activeBold, activeItalic)
                                            val newSel = TextRange(insIdx + 3)
                                            doc = updatedDoc
                                            textFieldValue = TextFieldValue(text = autoText, selection = newSel)
                                            triggerImmediateSave(updatedDoc = updatedDoc, selection = newSel, forceNewUndoStep = true)
                                            return@BasicTextField
                                        }
                                    }
                                }
                            }

                            if (newText != oldText) {
                                val updatedDoc = adjustDocumentOnEdit(doc, newText, activeBold, activeItalic)
                                doc = updatedDoc
                                textFieldValue = incoming
                                triggerImmediateSave(updatedDoc = updatedDoc, selection = incoming.selection)
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
                            .defaultMinSize(minHeight = 360.dp)
                            .focusRequester(bodyFocusRequester)
                    )
                }

                Spacer(Modifier.height(64.dp))
            }
        }

        // Bottom Studio Bar: Either Drawing Tools or Text Formatting Toolbar
        AnimatedVisibility(
            visible = isDrawingMode,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            DrawingToolbar(
                selectedTool = selectedTool,
                selectedColorIndex = selectedColorIndex,
                selectedSizeIndex = currentToolSizeIndex,
                hasStrokes = doc.strokes.isNotEmpty(),
                onSelectTool = { selectedTool = it },
                onSelectColor = { selectedColorIndex = it },
                onSelectSize = { newIdx ->
                    if (selectedTool == DrawingTool.ERASER) {
                        eraserSizeIndex = newIdx
                    } else {
                        penSizeIndex = newIdx
                    }
                },
                onClearCanvas = {
                    if (doc.strokes.isNotEmpty()) {
                        val updatedDoc = doc.copy(strokes = emptyList())
                        doc = updatedDoc
                        triggerImmediateSave(updatedDoc = updatedDoc, forceNewUndoStep = true)
                    }
                },
                onDoneDrawing = { isDrawingMode = false }
            )
        }

        AnimatedVisibility(
            visible = isTextToolbarVisible,
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
                        triggerImmediateSave(updatedDoc = updatedDoc, forceNewUndoStep = true)
                    },
                    onToggleBold = {
                        val sel = textFieldValue.selection
                        if (!sel.collapsed) {
                            val updatedDoc = toggleSelectionInlineStyle(doc, sel, toggleBold = true)
                            doc = updatedDoc
                            triggerImmediateSave(updatedDoc = updatedDoc, forceNewUndoStep = true)
                        } else {
                            activeBold = !activeBold
                        }
                    },
                    onToggleItalic = {
                        val sel = textFieldValue.selection
                        if (!sel.collapsed) {
                            val updatedDoc = toggleSelectionInlineStyle(doc, sel, toggleItalic = true)
                            doc = updatedDoc
                            triggerImmediateSave(updatedDoc = updatedDoc, forceNewUndoStep = true)
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
                val newSel = TextRange(insertStart + displayLabel.length)
                doc = finalDoc
                textFieldValue = TextFieldValue(text = newText, selection = newSel)
                triggerImmediateSave(updatedDoc = finalDoc, selection = newSel, forceNewUndoStep = true)
                showLinkDialog = false
            }
        )
    }
}

// ============================================================================
// FULL-WIDTH EDGE-TO-EDGE SKETCH CANVAS & CONTINUOUS SWIPE ERASER
// ============================================================================

@Composable
fun EdgeToEdgeSketchCanvas(
    strokes: List<DrawStroke>,
    canvasHeightDp: Int,
    isDrawingMode: Boolean,
    selectedTool: DrawingTool,
    selectedColorIndex: Int,
    selectedSizeIndex: Int,
    onActivateDrawingMode: () -> Unit,
    onCommitStrokes: (List<DrawStroke>) -> Unit,
    onExpandCanvas: () -> Unit
) {
    val density = LocalDensity.current.density
    val adaptiveInkColor = MaterialTheme.colorScheme.onBackground

    var workingStrokes by remember(strokes) { mutableStateOf(strokes) }
    var activeStrokePoints by remember { mutableStateOf<List<DrawPoint>>(emptyList()) }
    var eraserCursorDp by remember { mutableStateOf<DrawPoint?>(null) }

    val latestStrokes by rememberUpdatedState(workingStrokes)
    val latestTool by rememberUpdatedState(selectedTool)
    val latestColorIndex by rememberUpdatedState(selectedColorIndex)
    val latestSizeIndex by rememberUpdatedState(selectedSizeIndex)
    val latestOnCommit by rememberUpdatedState(onCommitStrokes)

    Column(modifier = Modifier.fillMaxWidth()) {
        if (isDrawingMode) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                thickness = 0.5.dp
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(canvasHeightDp.dp)
                .clipToBounds()
                .then(
                    if (!isDrawingMode) {
                        Modifier.clickable { onActivateDrawingMode() }
                    } else {
                        Modifier.pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()

                                val startPt = DrawPoint(
                                    xDp = down.position.x / density,
                                    yDp = down.position.y / density
                                )
                                val tool = latestTool
                                val sizeIdx = latestSizeIndex
                                val widthDp = strokeWidthForTool(tool, sizeIdx)
                                val eraserRadiusDp = widthDp * 0.5f

                                // Synchronous mutable state for this gesture so fast swiping never loses previous frame edits
                                var gestureStrokes = latestStrokes
                                var lastPt = startPt

                                if (tool == DrawingTool.ERASER) {
                                    eraserCursorDp = startPt
                                    gestureStrokes = eraseStrokesAlongSegment(
                                        strokes = gestureStrokes,
                                        fromPt = startPt,
                                        toPt = startPt,
                                        eraserRadiusDp = eraserRadiusDp
                                    )
                                    workingStrokes = gestureStrokes
                                } else {
                                    activeStrokePoints = listOf(startPt)
                                }

                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                        ?: event.changes.firstOrNull()
                                    if (change != null && change.pressed) {
                                        change.consume()
                                        val currentPt = DrawPoint(
                                            xDp = change.position.x / density,
                                            yDp = change.position.y / density
                                        )
                                        if (tool == DrawingTool.ERASER) {
                                            eraserCursorDp = currentPt
                                            gestureStrokes = eraseStrokesAlongSegment(
                                                strokes = gestureStrokes,
                                                fromPt = lastPt,
                                                toPt = currentPt,
                                                eraserRadiusDp = eraserRadiusDp
                                            )
                                            workingStrokes = gestureStrokes
                                            lastPt = currentPt
                                        } else {
                                            if (hypot(currentPt.xDp - lastPt.xDp, currentPt.yDp - lastPt.yDp) >= 1.0f) {
                                                val interpolated = interpolatePoints(lastPt, currentPt, stepDp = 1.5f)
                                                activeStrokePoints = activeStrokePoints + interpolated
                                                lastPt = currentPt
                                            }
                                        }
                                    }
                                } while (event.changes.any { it.pressed })

                                eraserCursorDp = null
                                if (tool == DrawingTool.ERASER) {
                                    workingStrokes = gestureStrokes
                                    latestOnCommit(gestureStrokes)
                                } else if (activeStrokePoints.isNotEmpty()) {
                                    val newStroke = DrawStroke(
                                        tool = tool,
                                        colorIndex = latestColorIndex,
                                        widthDp = widthDp,
                                        points = activeStrokePoints
                                    )
                                    val committed = gestureStrokes + newStroke
                                    activeStrokePoints = emptyList()
                                    workingStrokes = committed
                                    latestOnCommit(committed)
                                }
                            }
                        }
                    }
                )
        ) {
            if (workingStrokes.isEmpty() && activeStrokePoints.isEmpty() && isDrawingMode) {
                Text(
                    text = "Draw anywhere across the screen…",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                fun drawSingleStroke(stroke: DrawStroke) {
                    val pts = stroke.points
                    if (pts.isEmpty()) return
                    val baseColor = resolveInkColor(stroke.colorIndex, adaptiveInkColor)
                    val paintColor = baseColor.copy(alpha = stroke.tool.alpha)
                    val strokePx = stroke.widthDp * density

                    if (pts.size == 1) {
                        drawCircle(
                            color = paintColor,
                            radius = strokePx / 2f,
                            center = Offset(pts[0].xDp * density, pts[0].yDp * density)
                        )
                    } else {
                        val path = Path().apply {
                            moveTo(pts[0].xDp * density, pts[0].yDp * density)
                            for (i in 1 until pts.size) {
                                val prev = pts[i - 1]
                                val cur = pts[i]
                                val midX = (prev.xDp + cur.xDp) * 0.5f * density
                                val midY = (prev.yDp + cur.yDp) * 0.5f * density
                                quadraticTo(
                                    prev.xDp * density,
                                    prev.yDp * density,
                                    midX,
                                    midY
                                )
                            }
                            val last = pts.last()
                            lineTo(last.xDp * density, last.yDp * density)
                        }
                        drawPath(
                            path = path,
                            color = paintColor,
                            style = Stroke(
                                width = strokePx,
                                cap = if (stroke.tool == DrawingTool.HIGHLIGHTER) StrokeCap.Square else StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }
                }

                workingStrokes.forEach { drawSingleStroke(it) }

                if (activeStrokePoints.isNotEmpty() && selectedTool != DrawingTool.ERASER) {
                    drawSingleStroke(
                        DrawStroke(
                            tool = selectedTool,
                            colorIndex = selectedColorIndex,
                            widthDp = strokeWidthForTool(selectedTool, selectedSizeIndex),
                            points = activeStrokePoints
                        )
                    )
                }

                // Live Eraser Ring matching the exact erasing radius
                eraserCursorDp?.let { cursor ->
                    val radiusPx = (strokeWidthForTool(DrawingTool.ERASER, selectedSizeIndex) * 0.5f) * density
                    drawCircle(
                        color = adaptiveInkColor.copy(alpha = 0.12f),
                        radius = radiusPx,
                        center = Offset(cursor.xDp * density, cursor.yDp * density)
                    )
                    drawCircle(
                        color = adaptiveInkColor.copy(alpha = 0.55f),
                        radius = radiusPx,
                        center = Offset(cursor.xDp * density, cursor.yDp * density),
                        style = Stroke(width = 1.2f * density)
                    )
                }
            }
        }

        if (isDrawingMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "+ More drawing space",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onExpandCanvas() }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                thickness = 0.5.dp
            )
        }
    }
}

@Composable
fun DrawingToolbar(
    selectedTool: DrawingTool,
    selectedColorIndex: Int,
    selectedSizeIndex: Int,
    hasStrokes: Boolean,
    onSelectTool: (DrawingTool) -> Unit,
    onSelectColor: (Int) -> Unit,
    onSelectSize: (Int) -> Unit,
    onClearCanvas: () -> Unit,
    onDoneDrawing: () -> Unit
) {
    val adaptiveInkColor = MaterialTheme.colorScheme.onBackground

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Row 1: Pens (Pen, Marker, Highlighter), Eraser, Clear, Done
            Row(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DrawingTool.entries.forEach { tool ->
                    val active = selectedTool == tool
                    Surface(
                        color = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelectTool(tool) }
                    ) {
                        Text(
                            text = tool.label,
                            fontSize = 13.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (active) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }

                if (hasStrokes) {
                    TextButton(
                        onClick = onClearCanvas,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Clear",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                TextButton(
                    onClick = onDoneDrawing,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Done",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Row 2: Ink Color Swatches (for Pens) + 5 Precision Sizes (XS, S, M, L, XL)
            Row(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (selectedTool != DrawingTool.ERASER) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        InkPaletteSwatches.forEachIndexed { idx, swatch ->
                            val displayColor = if (idx == 0) adaptiveInkColor else swatch
                            val isSelected = selectedColorIndex == idx
                            Box(
                                modifier = Modifier
                                    .size(25.dp)
                                    .clip(CircleShape)
                                    .background(displayColor)
                                    .border(
                                        width = if (isSelected) 2.5.dp else 1.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline,
                                        shape = CircleShape
                                    )
                                    .clickable { onSelectColor(idx) }
                            )
                        }
                    }
                } else {
                    Text(
                        text = "Eraser size:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SizePresetLabels.forEachIndexed { idx, sizeLabel ->
                        val active = selectedSizeIndex == idx
                        Surface(
                            color = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .height(28.dp)
                                .defaultMinSize(minWidth = 30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSelectSize(idx) }
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 7.dp)
                            ) {
                                Text(
                                    text = sizeLabel,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (active) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// TEXT FORMATTING TOOLBAR
// ============================================================================

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

// ============================================================================
// DIALOGS (NEW FOLDER, RENAME FOLDER, MOVE TO FOLDER, RENAME, DELETE, SETTINGS)
// ============================================================================

@Composable
fun NewFolderDialog(
    parentFolderPath: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var folderName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = if (parentFolderPath.isEmpty()) "New folder"
                else "New folder in “${folderDisplayName(parentFolderPath)}”",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                placeholder = { Text("Folder name") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                enabled = folderName.trim().isNotEmpty(),
                onClick = { onConfirm(folderName) }
            ) {
                Text("Create", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun RenameFolderDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var folderName by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Rename folder", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                placeholder = { Text("Folder name") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                enabled = folderName.trim().isNotEmpty(),
                onClick = { onConfirm(folderName) }
            ) {
                Text("Save", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun DeleteFolderDialog(
    folderName: String,
    onDismiss: () -> Unit,
    onConfirmDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Delete folder?", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Text(
                text = "“$folderName” will be removed. Any notes inside it will be moved up safely.",
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
fun MoveToFolderDialog(
    currentFolder: String,
    folders: List<String>,
    onDismiss: () -> Unit,
    onSelectFolder: (String) -> Unit,
    onCreateNewFolder: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Move to folder", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    color = if (currentFolder.isBlank()) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelectFolder("") }
                ) {
                    Text(
                        text = "Folio (Home)",
                        fontSize = 15.sp,
                        fontWeight = if (currentFolder.isBlank()) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }

                folders.forEach { folderPath ->
                    val depth = folderPath.count { it == '/' }
                    val selected = currentFolder.equals(folderPath, ignoreCase = true)
                    Surface(
                        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelectFolder(folderPath) }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(
                                start = (12 + depth * 16).dp,
                                end = 12.dp,
                                top = 10.dp,
                                bottom = 10.dp
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = folderDisplayName(folderPath),
                                fontSize = 15.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onCreateNewFolder) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New folder")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
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
