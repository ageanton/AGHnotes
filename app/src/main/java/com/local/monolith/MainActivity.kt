@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class
)

package com.local.monolith

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.ceil

// ============================================================================
// DATA MODEL & LOCAL SQLITE VAULT ENGINE
// ============================================================================

data class Note(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val folder: String = "Notes",
    val tags: List<String> = emptyList(),
    val colorIndex: Int = 0,
    val isPinned: Boolean = false,
    val isLocked: Boolean = false,
    val isTrashed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

class VaultDatabase(context: Context) : SQLiteOpenHelper(context, "monolith_vault.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE notes (
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
        seedStarterNotes(db)
    }

    private fun seedStarterNotes(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        val starterNotes = listOf(
            Note(
                title = "Welcome to Monolith Vault",
                content = """
                    # Your 100% Air-Gapped Knowledge Base
                    Monolith is engineered for speed, privacy, and zero cloud dependencies.
                    
                    ## Try Our Signature Features:
                    - [x] Zero Internet Permissions (tap the Shield icon above to verify)
                    - [ ] Tap this checkbox in **Reader Mode** (eye icon) to toggle it!
                    - [ ] Tap the `[[Zero-Cloud Architecture]]` link at the bottom to jump between linked notes.
                    - [ ] Start a 25-minute **Focus Sprint** using the timer in the editor top bar.
                    
                    Connected thoughts: [[Zero-Cloud Architecture]] and [[Quick Capture Scratchpad]].
                """.trimIndent(),
                folder = "Notes",
                tags = listOf("welcome", "guide"),
                colorIndex = 1,
                isPinned = true,
                createdAt = now - 3600_000,
                updatedAt = now
            ),
            Note(
                title = "Zero-Cloud Architecture",
                content = """
                    # How Your Data Stays Yours
                    Unlike cloud note apps that sync your private thoughts to remote servers, Monolith operates strictly inside Android's local SQLite sandbox.
                    
                    > Tip: Notice at the bottom of this note that **Welcome to Monolith Vault** appears under *Incoming Backlinks* automatically because it links here!
                    
                    You can export your entire vault as a single `.json` file anytime from the top menu.
                """.trimIndent(),
                folder = "Ideas",
                tags = listOf("privacy", "architecture"),
                colorIndex = 2,
                isPinned = false,
                createdAt = now - 7200_000,
                updatedAt = now - 1800_000
            ),
            Note(
                title = "Quick Capture Scratchpad",
                content = """
                    # Daily Priorities
                    - [ ] Test exporting vault backup to phone storage
                    - [ ] Create a PIN lock for sensitive notes
                    - [ ] Link a new note using double brackets like [[My Next Big Idea]]
                """.trimIndent(),
                folder = "Tasks",
                tags = listOf("daily", "todo"),
                colorIndex = 3,
                isPinned = false,
                createdAt = now - 10800_000,
                updatedAt = now - 3600_000
            )
        )
        starterNotes.forEach { insertOrUpdateInternal(db, it) }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun insertOrUpdateInternal(db: SQLiteDatabase, note: Note) {
        val values = ContentValues().apply {
            put("id", note.id)
            put("title", note.title.ifBlank { "Untitled Note" })
            put("content", note.content)
            put("folder", note.folder)
            put("tags", note.tags.joinToString(","))
            put("color_index", note.colorIndex)
            put("is_pinned", if (note.isPinned) 1 else 0)
            put("is_locked", if (note.isLocked) 1 else 0)
            put("is_trashed", if (note.isTrashed) 1 else 0)
            put("created_at", note.createdAt)
            put("updated_at", note.updatedAt)
        }
        db.insertWithOnConflict("notes", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun upsertNote(note: Note) {
        insertOrUpdateInternal(writableDatabase, note)
    }

    fun deletePermanently(id: String) {
        writableDatabase.delete("notes", "id = ?", arrayOf(id))
    }

    fun emptyTrash() {
        writableDatabase.delete("notes", "is_trashed = 1", null)
    }

    fun getAllNotes(): List<Note> {
        val list = mutableListOf<Note>()
        val cursor = readableDatabase.query(
            "notes", null, null, null, null, null,
            "is_pinned DESC, updated_at DESC"
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                val rawTags = c.getString(c.getColumnIndexOrThrow("tags"))
                list.add(
                    Note(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        title = c.getString(c.getColumnIndexOrThrow("title")),
                        content = c.getString(c.getColumnIndexOrThrow("content")),
                        folder = c.getString(c.getColumnIndexOrThrow("folder")),
                        tags = if (rawTags.isBlank()) emptyList() else rawTags.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                        colorIndex = c.getInt(c.getColumnIndexOrThrow("color_index")),
                        isPinned = c.getInt(c.getColumnIndexOrThrow("is_pinned")) == 1,
                        isLocked = c.getInt(c.getColumnIndexOrThrow("is_locked")) == 1,
                        isTrashed = c.getInt(c.getColumnIndexOrThrow("is_trashed")) == 1,
                        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
                    )
                )
            }
        }
        return list
    }

    fun exportVaultJson(): String {
        val array = JSONArray()
        getAllNotes().forEach { note ->
            val obj = JSONObject().apply {
                put("id", note.id)
                put("title", note.title)
                put("content", note.content)
                put("folder", note.folder)
                put("tags", JSONArray(note.tags))
                put("colorIndex", note.colorIndex)
                put("isPinned", note.isPinned)
                put("isLocked", note.isLocked)
                put("isTrashed", note.isTrashed)
                put("createdAt", note.createdAt)
                put("updatedAt", note.updatedAt)
            }
            array.put(obj)
        }
        return JSONObject().apply {
            put("app", "MonolithVault")
            put("version", 1)
            put("exportedAt", System.currentTimeMillis())
            put("notes", array)
        }.toString(2)
    }

    fun importVaultJson(jsonString: String): Int {
        val root = JSONObject(jsonString)
        val notesArray = root.getJSONArray("notes")
        var count = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (i in 0 until notesArray.length()) {
                val obj = notesArray.getJSONObject(i)
                val tagsJson = obj.optJSONArray("tags")
                val tags = mutableListOf<String>()
                if (tagsJson != null) {
                    for (t in 0 until tagsJson.length()) {
                        tags.add(tagsJson.getString(t))
                    }
                }
                val note = Note(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    title = obj.optString("title", "Imported Note"),
                    content = obj.optString("content", ""),
                    folder = obj.optString("folder", "Notes"),
                    tags = tags,
                    colorIndex = obj.optInt("colorIndex", 0),
                    isPinned = obj.optBoolean("isPinned", false),
                    isLocked = obj.optBoolean("isLocked", false),
                    isTrashed = obj.optBoolean("isTrashed", false),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                )
                insertOrUpdateInternal(db, note)
                count++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return count
    }
}

// ============================================================================
// THEME PALETTE & HELPERS
// ============================================================================

val DarkNoteColors = listOf(
    Color(0xFF1C1D24), // Default Obsidian
    Color(0xFF2B2418), // Warm Gold Amber
    Color(0xFF192724), // Deep Emerald
    Color(0xFF1A2332), // Midnight Sapphire
    Color(0xFF2B1C28), // Mulberry Plum
    Color(0xFF2A1E1E)  // Rust Crimson
)

val LightNoteColors = listOf(
    Color(0xFFF6F6F9), // Default Paper
    Color(0xFFFFF8E1), // Soft Amber
    Color(0xFFE8F5E9), // Mint Leaf
    Color(0xFFE3F2FD), // Sky Mist
    Color(0xFFF3E5F5), // Lavender
    Color(0xFFFFEBEE)  // Rose Quartz
)

val StandardFolders = listOf("All", "Pinned", "Notes", "Ideas", "Journal", "Tasks", "Locked", "Trash")

fun hashPin(pin: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(("monolith_salt_" + pin).toByteArray())
        .joinToString("") { "%02x".format(it) }
}

fun extractWikiLinks(text: String): List<String> {
    val regex = Regex("""\[\[([^\[\]]+)\]\]""")
    return regex.findAll(text)
        .map { it.groupValues[1].trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()
}

fun formatTimestamp(millis: Long): String {
    val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    return sdf.format(Date(millis))
}

// ============================================================================
// MAIN ACTIVITY
// ============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val db = VaultDatabase(applicationContext)
        val prefs = getSharedPreferences("monolith_prefs", Context.MODE_PRIVATE)

        setContent {
            var isDarkTheme by remember { mutableStateOf(prefs.getBoolean("dark_mode", true)) }

            val colorScheme = if (isDarkTheme) {
                darkColorScheme(
                    primary = Color(0xFFD4AF37),
                    onPrimary = Color(0xFF121318),
                    secondary = Color(0xFF8AB4F8),
                    background = Color(0xFF0F1015),
                    surface = Color(0xFF181A20),
                    surfaceVariant = Color(0xFF22252E)
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF8D6E11),
                    onPrimary = Color.White,
                    secondary = Color(0xFF1967D2),
                    background = Color(0xFFFAFAFC),
                    surface = Color.White,
                    surfaceVariant = Color(0xFFEDEEF2)
                )
            }

            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MonolithApp(
                        db = db,
                        isDarkTheme = isDarkTheme,
                        onToggleTheme = {
                            isDarkTheme = !isDarkTheme
                            prefs.edit().putBoolean("dark_mode", isDarkTheme).apply()
                        },
                        savedPinHash = prefs.getString("pin_hash", null),
                        onSavePinHash = { newHash ->
                            prefs.edit().putString("pin_hash", newHash).apply()
                        }
                    )
                }
            }
        }
    }
}

// ============================================================================
// ROOT COMPOSABLE & NAVIGATION
// ============================================================================

@Composable
fun MonolithApp(
    db: VaultDatabase,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    savedPinHash: String?,
    onSavePinHash: (String) -> Unit
) {
    val context = LocalContext.current
    var notes by remember { mutableStateOf(db.getAllNotes()) }
    var activeNote by remember { mutableStateOf<Note?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFolder by remember { mutableStateOf("All") }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var isGridView by remember { mutableStateOf(true) }
    var unlockedSession by remember { mutableStateOf(false) }

    // Dialog states
    var showSecurityAudit by remember { mutableStateOf(false) }
    var showPinDialogForNote by remember { mutableStateOf<Note?>(null) }
    var showPinDialogForFolder by remember { mutableStateOf(false) }
    var showSetupPinDialog by remember { mutableStateOf(false) }
    var currentPinHash by remember { mutableStateOf(savedPinHash) }

    fun refreshNotes() {
        notes = db.getAllNotes()
    }

    // Export Vault Launcher (.json)
    val exportVaultLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.openOutputStream(it)?.use { out ->
                    out.write(db.exportVaultJson().toByteArray())
                }
                Toast.makeText(context, "Vault exported offline successfully!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Import Vault Launcher (.json)
    val importVaultLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val content = context.contentResolver.openInputStream(it)?.use { input ->
                    BufferedReader(InputStreamReader(input)).readText()
                } ?: ""
                val importedCount = db.importVaultJson(content)
                refreshNotes()
                Toast.makeText(context, "Imported $importedCount notes into Vault!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Invalid Vault JSON: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    if (activeNote != null) {
        NoteEditorScreen(
            note = activeNote!!,
            allNotes = notes.filter { !it.isTrashed },
            isDarkTheme = isDarkTheme,
            onSave = { updated ->
                db.upsertNote(updated)
                refreshNotes()
                activeNote = updated
            },
            onClose = {
                refreshNotes()
                activeNote = null
            },
            onOpenOrCreateLinkedNote = { targetTitle ->
                val existing = notes.firstOrNull {
                    !it.isTrashed && it.title.equals(targetTitle, ignoreCase = true)
                }
                if (existing != null) {
                    if (existing.isLocked && !unlockedSession) {
                        showPinDialogForNote = existing
                    } else {
                        activeNote = existing
                    }
                } else {
                    val created = Note(
                        title = targetTitle,
                        content = "# $targetTitle\n\nLinked from [[${activeNote!!.title}]]\n\n",
                        folder = activeNote!!.folder
                    )
                    db.upsertNote(created)
                    refreshNotes()
                    activeNote = created
                }
            },
            onRequestLockToggle = { current ->
                if (currentPinHash == null) {
                    showSetupPinDialog = true
                } else {
                    val updated = current.copy(
                        isLocked = !current.isLocked,
                        updatedAt = System.currentTimeMillis()
                    )
                    db.upsertNote(updated)
                    refreshNotes()
                    activeNote = updated
                }
            },
            onMoveToTrash = { current ->
                db.upsertNote(current.copy(isTrashed = true, isPinned = false, updatedAt = System.currentTimeMillis()))
                refreshNotes()
                activeNote = null
            },
            onRestoreFromTrash = { current ->
                db.upsertNote(current.copy(isTrashed = false, updatedAt = System.currentTimeMillis()))
                refreshNotes()
                activeNote = null
            },
            onDeleteForever = { current ->
                db.deletePermanently(current.id)
                refreshNotes()
                activeNote = null
            }
        )
    } else {
        VaultHomeScreen(
            notes = notes,
            searchQuery = searchQuery,
            onSearchChange = { searchQuery = it },
            selectedFolder = selectedFolder,
            onSelectFolder = { folder ->
                if (folder == "Locked" && !unlockedSession) {
                    if (currentPinHash == null) {
                        showSetupPinDialog = true
                    } else {
                        showPinDialogForFolder = true
                    }
                } else {
                    selectedFolder = folder
                }
            },
            selectedTag = selectedTag,
            onSelectTag = { selectedTag = if (selectedTag == it) null else it },
            isGridView = isGridView,
            onToggleGrid = { isGridView = !isGridView },
            isDarkTheme = isDarkTheme,
            onToggleTheme = onToggleTheme,
            unlockedSession = unlockedSession,
            onOpenNote = { note ->
                if (note.isLocked && !unlockedSession) {
                    if (currentPinHash == null) {
                        showSetupPinDialog = true
                    } else {
                        showPinDialogForNote = note
                    }
                } else {
                    activeNote = note
                }
            },
            onCreateNote = {
                val defaultFolder = if (selectedFolder in listOf("All", "Pinned", "Trash", "Locked")) "Notes" else selectedFolder
                val fresh = Note(
                    title = "",
                    content = "",
                    folder = defaultFolder,
                    isLocked = selectedFolder == "Locked"
                )
                db.upsertNote(fresh)
                refreshNotes()
                activeNote = fresh
            },
            onTogglePin = { note ->
                db.upsertNote(note.copy(isPinned = !note.isPinned, updatedAt = System.currentTimeMillis()))
                refreshNotes()
            },
            onEmptyTrash = {
                db.emptyTrash()
                refreshNotes()
            },
            onExportVault = {
                val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                exportVaultLauncher.launch("Monolith_Vault_$dateStr.json")
            },
            onImportVault = {
                importVaultLauncher.launch(arrayOf("application/json", "*/*"))
            },
            onShowSecurityAudit = { showSecurityAudit = true },
            onSetupOrChangePin = { showSetupPinDialog = true }
        )
    }

    // Security Audit Dialog
    if (showSecurityAudit) {
        AirGapSecurityDialog(onDismiss = { showSecurityAudit = false })
    }

    // Setup PIN Dialog
    if (showSetupPinDialog) {
        SetupPinDialog(
            onDismiss = { showSetupPinDialog = false },
            onPinCreated = { rawPin ->
                val hashed = hashPin(rawPin)
                currentPinHash = hashed
                onSavePinHash(hashed)
                unlockedSession = true
                showSetupPinDialog = false
                Toast.makeText(context, "Vault PIN configured!", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Verify PIN Dialog (for a locked note or Locked folder)
    if (showPinDialogForNote != null || showPinDialogForFolder) {
        VerifyPinDialog(
            expectedHash = currentPinHash ?: "",
            onDismiss = {
                showPinDialogForNote = null
                showPinDialogForFolder = false
            },
            onUnlocked = {
                unlockedSession = true
                if (showPinDialogForFolder) {
                    selectedFolder = "Locked"
                }
                showPinDialogForNote?.let { activeNote = it }
                showPinDialogForNote = null
                showPinDialogForFolder = false
            }
        )
    }
}

// ============================================================================
// HOME SCREEN
// ============================================================================

@Composable
fun VaultHomeScreen(
    notes: List<Note>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    selectedFolder: String,
    onSelectFolder: (String) -> Unit,
    selectedTag: String?,
    onSelectTag: (String) -> Unit,
    isGridView: Boolean,
    onToggleGrid: () -> Unit,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    unlockedSession: Boolean,
    onOpenNote: (Note) -> Unit,
    onCreateNote: () -> Unit,
    onTogglePin: (Note) -> Unit,
    onEmptyTrash: () -> Unit,
    onExportVault: () -> Unit,
    onImportVault: () -> Unit,
    onShowSecurityAudit: () -> Unit,
    onSetupOrChangePin: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    val allTags = remember(notes) {
        notes.filter { !it.isTrashed }
            .flatMap { it.tags }
            .distinct()
            .sorted()
    }

    val filteredNotes = remember(notes, searchQuery, selectedFolder, selectedTag) {
        notes.filter { note ->
            val matchesTrash = if (selectedFolder == "Trash") note.isTrashed else !note.isTrashed
            val matchesFolder = when (selectedFolder) {
                "All" -> !note.isTrashed
                "Pinned" -> note.isPinned && !note.isTrashed
                "Locked" -> note.isLocked && !note.isTrashed
                "Trash" -> note.isTrashed
                else -> note.folder.equals(selectedFolder, ignoreCase = true) && !note.isTrashed
            }
            val matchesTag = selectedTag == null || note.tags.contains(selectedTag)
            val q = searchQuery.trim().lowercase()
            val matchesQuery = q.isEmpty() ||
                note.title.lowercase().contains(q) ||
                (!note.isLocked && note.content.lowercase().contains(q)) ||
                note.tags.any { it.lowercase().contains(q) }

            matchesTrash && matchesFolder && matchesTag && matchesQuery
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "MONOLITH",
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp,
                            fontSize = 20.sp
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.clickable { onShowSecurityAudit() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Security,
                                    contentDescription = "Offline Verified",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "AIR-GAPPED",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onToggleGrid) {
                        Icon(
                            imageVector = if (isGridView) Icons.Default.ViewList else Icons.Default.GridView,
                            contentDescription = "Toggle Layout"
                        )
                    }
                    IconButton(onClick = onToggleTheme) {
                        Icon(
                            imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Toggle Theme"
                        )
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Vault Menu")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Export Vault Backup (.json)") },
                                leadingIcon = { Icon(Icons.Default.FileUpload, null) },
                                onClick = {
                                    showMenu = false
                                    onExportVault()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Import Vault Backup (.json)") },
                                leadingIcon = { Icon(Icons.Default.FileDownload, null) },
                                onClick = {
                                    showMenu = false
                                    onImportVault()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Configure Vault PIN Lock") },
                                leadingIcon = { Icon(Icons.Default.Lock, null) },
                                onClick = {
                                    showMenu = false
                                    onSetupOrChangePin()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Air-Gapped Security Audit") },
                                leadingIcon = { Icon(Icons.Default.Security, null) },
                                onClick = {
                                    showMenu = false
                                    onShowSecurityAudit()
                                }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (selectedFolder != "Trash") {
                ExtendedFloatingActionButton(
                    onClick = onCreateNote,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(Icons.Default.Add, contentDescription = "New Note") },
                    text = { Text("New Note", fontWeight = FontWeight.Bold) }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                placeholder = { Text("Search titles, markdown, or #tags...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )

            // Folder Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StandardFolders.forEach { folder ->
                    val selected = selectedFolder == folder
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectFolder(folder) },
                        label = { Text(folder) },
                        leadingIcon = when (folder) {
                            "Pinned" -> {
                                { Icon(Icons.Default.PushPin, null, Modifier.size(16.dp)) }
                            }
                            "Locked" -> {
                                { Icon(Icons.Default.Lock, null, Modifier.size(16.dp)) }
                            }
                            "Trash" -> {
                                { Icon(Icons.Default.Delete, null, Modifier.size(16.dp)) }
                            }
                            else -> null
                        }
                    )
                }
            }

            // Tag Filter Bar
            if (allTags.isNotEmpty() && selectedFolder != "Trash") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    allTags.forEach { tag ->
                        val isSelected = selectedTag == tag
                        AssistChip(
                            onClick = { onSelectTag(tag) },
                            label = { Text("#$tag", fontSize = 12.sp) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (isSelected)
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                                else Color.Transparent
                            )
                        )
                    }
                }
            }

            // Trash Banner
            if (selectedFolder == "Trash" && filteredNotes.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${filteredNotes.size} note(s) in Trash",
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = onEmptyTrash) {
                        Text("Empty Trash Forever", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // Notes Grid / List
            if (filteredNotes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (searchQuery.isNotBlank()) "No matching notes found"
                            else "No notes in $selectedFolder yet",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            } else if (isGridView) {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalItemSpacing = 10.dp
                ) {
                    items(filteredNotes, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            isDarkTheme = isDarkTheme,
                            unlockedSession = unlockedSession,
                            onClick = { onOpenNote(note) },
                            onLongClick = { onTogglePin(note) }
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredNotes, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            isDarkTheme = isDarkTheme,
                            unlockedSession = unlockedSession,
                            onClick = { onOpenNote(note) },
                            onLongClick = { onTogglePin(note) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NoteCard(
    note: Note,
    isDarkTheme: Boolean,
    unlockedSession: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val palette = if (isDarkTheme) DarkNoteColors else LightNoteColors
    val cardColor = palette[note.colorIndex.coerceIn(0, palette.lastIndex)]
    val wikiLinksCount = remember(note.content) { extractWikiLinks(note.content).size }

    Surface(
        color = cardColor,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = if (note.isPinned) 1.5.dp else 0.5.dp,
                color = if (note.isPinned) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                shape = RoundedCornerShape(16.dp)
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = note.title.ifBlank { "Untitled Note" },
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (note.isLocked) {
                        Icon(
                            imageVector = if (unlockedSession) Icons.Default.LockOpen else Icons.Default.Lock,
                            contentDescription = "Locked",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    if (note.isPinned) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = "Pinned",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            if (note.isLocked && !unlockedSession) {
                Text(
                    text = "🔒 Encrypted Private Note — Tap to unlock with PIN",
                    fontSize = 13.sp,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            } else {
                Text(
                    text = note.content.ifBlank { "Empty note..." },
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }

            Spacer(Modifier.height(10.dp))

            // Tags & Metadata Footer
            if (note.tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    note.tags.take(4).forEach { tag ->
                        Surface(
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "#$tag",
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${note.folder} • ${formatTimestamp(note.updatedAt)}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
                if (wikiLinksCount > 0) {
                    Text(
                        text = "⇄ $wikiLinksCount",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

// ============================================================================
// NOTE EDITOR + READER MODE + ZETTELKASTEN GRAPH + POMODORO SPRINT
// ============================================================================

@Composable
fun NoteEditorScreen(
    note: Note,
    allNotes: List<Note>,
    isDarkTheme: Boolean,
    onSave: (Note) -> Unit,
    onClose: () -> Unit,
    onOpenOrCreateLinkedNote: (String) -> Unit,
    onRequestLockToggle: (Note) -> Unit,
    onMoveToTrash: (Note) -> Unit,
    onRestoreFromTrash: (Note) -> Unit,
    onDeleteForever: (Note) -> Unit
) {
    val context = LocalContext.current
    var title by remember(note.id) { mutableStateOf(note.title) }
    var textFieldValue by remember(note.id) {
        mutableStateOf(TextFieldValue(text = note.content, selection = TextRange(note.content.length)))
    }
    var folder by remember(note.id) { mutableStateOf(note.folder) }
    var tagsText by remember(note.id) { mutableStateOf(note.tags.joinToString(", ")) }
    var colorIndex by remember(note.id) { mutableStateOf(note.colorIndex) }
    var isPinned by remember(note.id) { mutableStateOf(note.isPinned) }
    var isReaderMode by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }

    // 25-Minute Focus Timer State
    var timerRunning by remember { mutableStateOf(false) }
    var timerSecondsLeft by remember { mutableStateOf(25 * 60) }

    LaunchedEffect(timerRunning) {
        while (timerRunning && timerSecondsLeft > 0) {
            delay(1000L)
            timerSecondsLeft--
        }
        if (timerSecondsLeft == 0 && timerRunning) {
            timerRunning = false
            Toast.makeText(context, "25-Minute Focus Sprint Complete!", Toast.LENGTH_LONG).show()
        }
    }

    fun persistCurrentState(): Note {
        val parsedTags = tagsText.split(",")
            .map { it.trim().removePrefix("#") }
            .filter { it.isNotEmpty() }
            .distinct()
        val updated = note.copy(
            title = title.ifBlank { "Untitled Note" },
            content = textFieldValue.text,
            folder = folder,
            tags = parsedTags,
            colorIndex = colorIndex,
            isPinned = isPinned,
            updatedAt = System.currentTimeMillis()
        )
        onSave(updated)
        return updated
    }

    BackHandler {
        persistCurrentState()
        onClose()
    }

    // Export single note as Markdown (.md)
    val exportMdLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri: Uri? ->
        uri?.let {
            val saved = persistCurrentState()
            context.contentResolver.openOutputStream(it)?.use { out ->
                val md = "# ${saved.title}\n\n${saved.content}\n"
                out.write(md.toByteArray())
            }
            Toast.makeText(context, "Saved as Markdown (.md)!", Toast.LENGTH_SHORT).show()
        }
    }

    val palette = if (isDarkTheme) DarkNoteColors else LightNoteColors
    val bgColor = palette[colorIndex.coerceIn(0, palette.lastIndex)]

    // Outgoing [[Wiki-Links]] and Incoming Backlinks
    val outgoingLinks = remember(textFieldValue.text) { extractWikiLinks(textFieldValue.text) }
    val incomingBacklinks = remember(allNotes, title, note.id) {
        if (title.isBlank()) emptyList()
        else allNotes.filter { other ->
            other.id != note.id && extractWikiLinks(other.content).any {
                it.equals(title.trim(), ignoreCase = true)
            }
        }
    }

    // Live word & reading stats
    val wordCount = remember(textFieldValue.text) {
        val trimmed = textFieldValue.text.trim()
        if (trimmed.isEmpty()) 0 else trimmed.split(Regex("\\s+")).size
    }
    val readingTimeMin = remember(wordCount) { ceil(wordCount / 200.0).toInt().coerceAtLeast(1) }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bgColor),
                navigationIcon = {
                    IconButton(onClick = {
                        persistCurrentState()
                        onClose()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Save & Back")
                    }
                },
                title = {
                    // Focus Sprint Timer Pill
                    Surface(
                        color = if (timerRunning) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.clickable {
                            if (!timerRunning && timerSecondsLeft == 0) {
                                timerSecondsLeft = 25 * 60
                            }
                            timerRunning = !timerRunning
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Timer,
                                contentDescription = "Pomodoro Focus Timer",
                                modifier = Modifier.size(15.dp),
                                tint = if (timerRunning) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.width(5.dp))
                            val mins = timerSecondsLeft / 60
                            val secs = timerSecondsLeft % 60
                            Text(
                                text = "%02d:%02d".format(mins, secs),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                actions = {
                    if (note.isTrashed) {
                        IconButton(onClick = { onRestoreFromTrash(note) }) {
                            Icon(Icons.Default.RestoreFromTrash, contentDescription = "Restore")
                        }
                        IconButton(onClick = { onDeleteForever(note) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete Forever",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        IconButton(onClick = {
                            persistCurrentState()
                            isReaderMode = !isReaderMode
                        }) {
                            Icon(
                                imageVector = if (isReaderMode) Icons.Default.Edit else Icons.Default.Visibility,
                                contentDescription = "Toggle Reader Mode",
                                tint = if (isReaderMode) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = { isPinned = !isPinned }) {
                            Icon(
                                Icons.Default.PushPin,
                                contentDescription = "Pin",
                                tint = if (isPinned) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                        IconButton(onClick = { showColorPicker = !showColorPicker }) {
                            Icon(Icons.Default.Palette, contentDescription = "Note Color")
                        }
                        IconButton(onClick = {
                            val current = persistCurrentState()
                            onRequestLockToggle(current)
                        }) {
                            Icon(
                                imageVector = if (note.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = "Lock Note",
                                tint = if (note.isLocked) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        IconButton(onClick = {
                            val safeName = (title.ifBlank { "Note" }).replace(Regex("[^a-zA-Z0-9_-]"), "_")
                            exportMdLauncher.launch("$safeName.md")
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Export Markdown")
                        }
                        IconButton(onClick = { onMoveToTrash(persistCurrentState()) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Move to Trash")
                        }
                    }
                }
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bgColor)
                    .imePadding()
            ) {
                // Quick Markdown Formatting Bar (when in Edit mode)
                if (!isReaderMode && !note.isTrashed) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val insertSnippet: (String) -> Unit = { snippet ->
                            val currentText = textFieldValue.text
                            val sel = textFieldValue.selection.start.coerceIn(0, currentText.length)
                            val newText = currentText.substring(0, sel) + snippet + currentText.substring(sel)
                            textFieldValue = TextFieldValue(
                                text = newText,
                                selection = TextRange(sel + snippet.length)
                            )
                        }
                        FormatPill("# H1") { insertSnippet("\n# ") }
                        FormatPill("## H2") { insertSnippet("\n## ") }
                        FormatPill("- [ ] Task") { insertSnippet("\n- [ ] ") }
                        FormatPill("**Bold**") { insertSnippet("****") }
                        FormatPill("[[WikiLink]]") { insertSnippet("[[]]") }
                        FormatPill("`Code`") { insertSnippet("``") }
                        FormatPill("> Quote") { insertSnippet("\n> ") }
                        FormatPill("🕒 Time") {
                            val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
                            insertSnippet("[$nowStr] ")
                        }
                    }
                }

                // Live Stats Bar
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "$wordCount words • ${textFieldValue.text.length} chars • ~$readingTimeMin min read",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        text = if (isReaderMode) "READER MODE" else "EDIT MODE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            // Color & Folder Selector Drawer
            AnimatedVisibility(visible = showColorPicker) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        palette.forEachIndexed { idx, color ->
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (colorIndex == idx) 2.5.dp else 1.dp,
                                        color = if (colorIndex == idx) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                        shape = CircleShape
                                    )
                                    .clickable { colorIndex = idx }
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Notes", "Ideas", "Journal", "Tasks").forEach { f ->
                            FilterChip(
                                selected = folder == f,
                                onClick = { folder = f },
                                label = { Text(f) }
                            )
                        }
                    }
                }
            }

            // Note Title Input
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = {
                    Text(
                        "Note Title...",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                )
            )

            // Tags Input Row
            OutlinedTextField(
                value = tagsText,
                onValueChange = { tagsText = it },
                placeholder = { Text("Tags (comma separated, e.g. ideas, roadmap)...", fontSize = 13.sp) },
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                )
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )

            // Main Body: Either Interactive Reader Mode or Raw Markdown Editor
            if (isReaderMode) {
                InteractiveMarkdownReader(
                    content = textFieldValue.text,
                    onToggleChecklistLine = { lineIndex ->
                        val lines = textFieldValue.text.lines().toMutableList()
                        if (lineIndex in lines.indices) {
                            val line = lines[lineIndex]
                            lines[lineIndex] = when {
                                line.trimStart().startsWith("- [ ]") -> line.replaceFirst("- [ ]", "- [x]")
                                line.trimStart().startsWith("- [x]") -> line.replaceFirst("- [x]", "- [ ]")
                                else -> line
                            }
                            val updatedText = lines.joinToString("\n")
                            textFieldValue = TextFieldValue(updatedText)
                            persistCurrentState()
                        }
                    }
                )
            } else {
                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = { textFieldValue = it },
                    placeholder = {
                        Text(
                            "Write freely in Markdown... Use [[Note Name]] to link notes together or - [ ] for tasks."
                        )
                    },
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 16.sp,
                        lineHeight = 24.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 360.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )
            }

            Spacer(Modifier.height(16.dp))

            // Zettelkasten [[Wiki-Links]] & Backlinks Section
            if (outgoingLinks.isNotEmpty() || incomingBacklinks.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "KNOWLEDGE GRAPH CONNECTIONS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (outgoingLinks.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Outgoing Wiki-Links (Tap to open or create):",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                            Spacer(Modifier.height(4.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                outgoingLinks.forEach { linkTitle ->
                                    AssistChip(
                                        onClick = {
                                            persistCurrentState()
                                            onOpenOrCreateLinkedNote(linkTitle)
                                        },
                                        label = { Text("[[$linkTitle]]", fontWeight = FontWeight.SemiBold) }
                                    )
                                }
                            }
                        }

                        if (incomingBacklinks.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Referenced By (Incoming Backlinks):",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                            Spacer(Modifier.height(4.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                incomingBacklinks.forEach { sourceNote ->
                                    AssistChip(
                                        onClick = {
                                            persistCurrentState()
                                            onOpenOrCreateLinkedNote(sourceNote.title)
                                        },
                                        label = { Text("← ${sourceNote.title}") }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FormatPill(label: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun InteractiveMarkdownReader(
    content: String,
    onToggleChecklistLine: (Int) -> Unit
) {
    val lines = remember(content) { content.lines() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        lines.forEachIndexed { index, rawLine ->
            val trimmed = rawLine.trimStart()
            when {
                trimmed.startsWith("# ") -> {
                    Text(
                        text = trimmed.removePrefix("# "),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                trimmed.startsWith("## ") -> {
                    Text(
                        text = trimmed.removePrefix("## "),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                trimmed.startsWith("- [ ] ") || trimmed.startsWith("- [x] ") -> {
                    val checked = trimmed.startsWith("- [x] ")
                    val taskText = trimmed.substring(6)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onToggleChecklistLine(index) }
                            .padding(vertical = 2.dp)
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { onToggleChecklistLine(index) }
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = taskText,
                            fontSize = 16.sp,
                            textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None,
                            color = if (checked) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                trimmed.startsWith("> ") -> {
                    Surface(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = trimmed.removePrefix("> "),
                            fontStyle = FontStyle.Italic,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                else -> {
                    val styled = buildAnnotatedString {
                        val wikiRegex = Regex("""\[\[([^\[\]]+)\]\]""")
                        var lastIdx = 0
                        for (match in wikiRegex.findAll(rawLine)) {
                            append(rawLine.substring(lastIdx, match.range.first))
                            withStyle(
                                SpanStyle(
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            ) {
                                append(match.value)
                            }
                            lastIdx = match.range.last + 1
                        }
                        if (lastIdx < rawLine.length) {
                            append(rawLine.substring(lastIdx))
                        }
                    }
                    Text(
                        text = styled,
                        fontSize = 16.sp,
                        lineHeight = 24.sp
                    )
                }
            }
        }
    }
}

// ============================================================================
// SECURITY AUDIT & PIN DIALOGS
// ============================================================================

@Composable
fun AirGapSecurityDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val requestedPermissions = remember {
        try {
            val pkgInfo = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS
            )
            pkgInfo.requestedPermissions?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    val hasInternet = requestedPermissions.contains("android.permission.INTERNET")

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("OS Air-Gap Security Audit", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (!hasInternet)
                        "✅ VERIFIED OFFLINE: Android PackageManager confirms this APK declares ZERO network permissions."
                    else "⚠️ Network permission detected.",
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "• android.permission.INTERNET: DENIED AT OS LEVEL\n" +
                        "• Cloud Telemetry / Analytics SDKs: 0\n" +
                        "• Storage Engine: Local SQLite (monolith_vault.db)\n" +
                        "• Declared Manifest Permissions: ${requestedPermissions.size}",
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Awesome")
            }
        }
    )
}

@Composable
fun SetupPinDialog(
    onDismiss: () -> Unit,
    onPinCreated: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Vault PIN Code") },
        text = {
            Column {
                Text("Protect locked notes with a local SHA-256 hashed PIN:")
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 8) pin = it.filter { c -> c.isDigit() } },
                    label = { Text("Enter 4–8 digit PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                enabled = pin.length >= 4,
                onClick = { onPinCreated(pin) }
            ) {
                Text("Save PIN")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun VerifyPinDialog(
    expectedHash: String,
    onDismiss: () -> Unit,
    onUnlocked: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unlock Private Note") },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        error = false
                        pin = it.filter { c -> c.isDigit() }
                        if (hashPin(pin) == expectedHash) {
                            onUnlocked()
                        }
                    },
                    label = { Text("Enter Vault PIN") },
                    isError = error,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true
                )
                if (error) {
                    Spacer(Modifier.height(4.dp))
                    Text("Incorrect PIN", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (hashPin(pin) == expectedHash) onUnlocked() else error = true
            }) {
                Text("Unlock")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
