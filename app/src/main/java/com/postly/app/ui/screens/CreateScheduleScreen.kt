package com.postly.app.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.postly.app.data.model.Platform
import com.postly.app.data.model.RepeatRule
import com.postly.app.data.model.ScheduleEntity
import com.postly.app.data.model.ScheduleStatus
import com.postly.app.service.PostlyAccessibilityService
import com.postly.app.util.AttachmentStore
import com.postly.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateScheduleScreen(
    platform: Platform,
    initialContactName: String = "",
    onBack: () -> Unit,
    onOpenRequirements: () -> Unit,
    onSaveSchedule: (ScheduleEntity) -> Unit
) {
    val context = LocalContext.current
    // Default schedule: 2 minutes in the future, exactly at 30 seconds
    val calendar = remember {
        Calendar.getInstance().apply {
            add(Calendar.MINUTE, 2)
            set(Calendar.SECOND, 30)
            set(Calendar.MILLISECOND, 0)
        }
    }

    var recipientName by remember { mutableStateOf(initialContactName) }
    var recipientPhone by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var scheduledMillis by remember { mutableStateOf(calendar.timeInMillis) }
    var repeatRule by remember { mutableStateOf(RepeatRule.NO_REPEAT) }
    var askBeforeSending by remember { mutableStateOf(false) }
    var generateThumbnails by remember { mutableStateOf(false) }
    var retryIntervalMinutes by remember { mutableStateOf<Int?>(null) }
    var isRetryMenuOpen by remember { mutableStateOf(false) }

    // Attachment State
    var attachmentUri by remember { mutableStateOf<String?>(null) }
    var attachmentType by remember { mutableStateOf<String?>(null) } // IMAGE, VIDEO, AUDIO, DOCUMENT
    var attachmentName by remember { mutableStateOf<String?>(null) }
    var attachmentSize by remember { mutableStateOf<String?>(null) }

    var isRecipientMenuOpen by remember { mutableStateOf(false) }
    var isManualContactDialogOpen by remember { mutableStateOf(false) }
    var isRepeatMenuOpen by remember { mutableStateOf(false) }
    var isTopMenuOpen by remember { mutableStateOf(false) }
    var isAttachmentSheetOpen by remember { mutableStateOf(false) }

    val dateFormatter = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }
    val timeFormatter = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }

    var displayDate by remember { mutableStateOf(dateFormatter.format(Date(scheduledMillis))) }
    var displayTime by remember { mutableStateOf(timeFormatter.format(Date(scheduledMillis))) }

    // Query file name and human-readable size
    fun getFileInfo(uri: Uri): Pair<String, String> {
        var name = "Attachment"
        var sizeStr = ""
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) name = cursor.getString(nameIndex) ?: "Attachment"
                    if (sizeIndex != -1) {
                        val size = cursor.getLong(sizeIndex)
                        sizeStr = if (size > 1024 * 1024) {
                            String.format(Locale.getDefault(), "%.1f MB", size / (1024.0 * 1024.0))
                        } else {
                            String.format(Locale.getDefault(), "%.1f KB", size / 1024.0)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            name = uri.lastPathSegment ?: "Attachment"
        }
        return Pair(name, sizeStr)
    }

    // Copies the picked file into app storage so it is still readable when the alarm fires
    val attachmentScope = rememberCoroutineScope()
    var isImportingAttachment by remember { mutableStateOf(false) }
    fun onAttachmentPicked(uri: Uri?, type: String) {
        if (uri == null) return
        val (name, size) = getFileInfo(uri)
        isImportingAttachment = true
        attachmentScope.launch {
            val stored = withContext(Dispatchers.IO) {
                AttachmentStore.importAttachment(context.applicationContext, uri, name)
            }
            isImportingAttachment = false
            if (stored == null) {
                Toast.makeText(context, "Couldn't attach that file. Please try another one.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            attachmentUri = stored.toString()
            attachmentType = type
            attachmentName = name
            attachmentSize = size
        }
    }

    // Launchers for various attachment types
    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> onAttachmentPicked(uri, "IMAGE") }

    val videoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> onAttachmentPicked(uri, "VIDEO") }

    val docLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> onAttachmentPicked(uri, "DOCUMENT") }

    val audioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> onAttachmentPicked(uri, "AUDIO") }

    // System Phonebook Picker Launcher
    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { uri: Uri? ->
        if (uri != null) {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIdx = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        recipientName = it.getString(nameIdx) ?: ""
                    }
                }
            }
        }
    }

    // Listen to contact picked via WhatsApp Accessibility
    LaunchedEffect(Unit) {
        PostlyAccessibilityService.onContactPickedCallback = { capturedName ->
            recipientName = capturedName
            recipientPhone = ""
        }
    }

    // Helper to build ScheduleEntity with strict 30-second timing
    fun buildScheduleEntity(status: ScheduleStatus): ScheduleEntity {
        val targetCal = Calendar.getInstance().apply {
            timeInMillis = scheduledMillis
            set(Calendar.SECOND, 30)
            set(Calendar.MILLISECOND, 0)
        }
        if (targetCal.timeInMillis <= System.currentTimeMillis()) {
            targetCal.add(Calendar.MINUTE, 1)
        }
        return ScheduleEntity(
            platform = platform.name,
            recipientName = recipientName,
            recipientPhone = recipientPhone,
            title = title,
            message = message,
            scheduledTimeMillis = targetCal.timeInMillis,
            repeatRule = repeatRule.name,
            askBeforeSending = askBeforeSending,
            status = status.name,
            attachmentUri = attachmentUri,
            attachmentType = attachmentType,
            attachmentName = attachmentName,
            attachmentSize = attachmentSize,
            retryIntervalMinutes = retryIntervalMinutes ?: 0,
            retryCount = 0,
            maxRetries = 3
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = platform.displayName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = BrandBlue)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenRequirements) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = "Requirements",
                            tint = WarningOrange
                        )
                    }
                    Box {
                        IconButton(onClick = { isTopMenuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More", tint = TextSecondary)
                        }
                        DropdownMenu(
                            expanded = isTopMenuOpen,
                            onDismissRequest = { isTopMenuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Save to drafts") },
                                onClick = {
                                    isTopMenuOpen = false
                                    onSaveSchedule(buildScheduleEntity(ScheduleStatus.DRAFTS))
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        bottomBar = {
            Surface(
                color = Color.White,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = {
                        if (recipientPhone.isNotBlank() || recipientName.isNotBlank()) {
                            onSaveSchedule(buildScheduleEntity(ScheduleStatus.PENDING))
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                    enabled = message.isNotBlank() && (recipientPhone.isNotBlank() || recipientName.isNotBlank())
                ) {
                    Text(
                        text = "Schedule Message",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(BackgroundLight)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Schedule Type Selector
            OutlinedCard(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.outlinedCardColors(containerColor = Color.White)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Schedule Type", fontSize = 11.sp, color = TextSecondary)
                        Text("Contacts / Groups", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    }
                    Icon(Icons.Default.ArrowDropDown, null, tint = TextSecondary)
                }
            }

            // Recipients Section
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recipients",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box {
                                IconButton(
                                    onClick = { isRecipientMenuOpen = true },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(BrandBlue, RoundedCornerShape(8.dp))
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Add", tint = Color.White)
                                }

                                DropdownMenu(
                                    expanded = isRecipientMenuOpen,
                                    onDismissRequest = { isRecipientMenuOpen = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Whatsapp") },
                                        leadingIcon = { Icon(Icons.Default.Send, null, Modifier.size(18.dp)) },
                                        onClick = {
                                            isRecipientMenuOpen = false
                                            PostlyAccessibilityService.isPickingContact = true
                                            val launchIntent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
                                            if (launchIntent != null) {
                                                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(launchIntent)
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Phonebook") },
                                        leadingIcon = { Icon(Icons.Default.MenuBook, null, Modifier.size(18.dp)) },
                                        onClick = {
                                            isRecipientMenuOpen = false
                                            contactPickerLauncher.launch(null)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Postly Lists") },
                                        leadingIcon = { Icon(Icons.Default.Group, null, Modifier.size(18.dp)) },
                                        onClick = { isRecipientMenuOpen = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Upload CSV") },
                                        leadingIcon = { Icon(Icons.Default.FileUpload, null, Modifier.size(18.dp)) },
                                        onClick = { isRecipientMenuOpen = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Enter Manually") },
                                        leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(18.dp)) },
                                        onClick = { isRecipientMenuOpen = false; isManualContactDialogOpen = true }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = if (recipientPhone.isNotBlank() || recipientName.isNotBlank()) "1/1" else "0/1",
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                        }
                    }

                    if (recipientPhone.isNotBlank() || recipientName.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = BrandBlueLight,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = recipientName.ifBlank { "WhatsApp Chat" },
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp,
                                        color = BrandBlueDark
                                    )
                                    if (recipientPhone.isNotBlank()) {
                                        Text(text = recipientPhone, fontSize = 12.sp, color = TextSecondary)
                                    }
                                }
                                IconButton(
                                    onClick = { recipientName = ""; recipientPhone = "" },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Close, null, tint = DangerRed, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }

            // Title Field
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Title", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Multiple messages", fontSize = 12.sp, color = TextSecondary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Checkbox(checked = false, onCheckedChange = {})
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { if (it.length <= 500) title = it },
                    placeholder = { Text("Title (Optional)", color = TextSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White
                    ),
                    supportingText = {
                        Text("${title.length}/500", modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                )
            }

            // Message Area
            Column {
                Text("Message", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .border(1.dp, BorderLight, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    TextField(
                        value = message,
                        onValueChange = { if (it.length <= 500) message = it },
                        placeholder = { Text("Message...", color = TextSecondary) },
                        modifier = Modifier
                            .weight(1f)
                            .height(130.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = BrandBlueLight,
                            modifier = Modifier
                                .size(36.dp)
                                .clickable { message += " {name} " }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("{ }", color = BrandBlue, fontWeight = FontWeight.Bold)
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = BrandBlueLight,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Feed, null, tint = BrandBlue, modifier = Modifier.size(18.dp))
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = BrandBlueLight,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.MyLocation, null, tint = BrandBlue, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { /* AI Assistant */ }) {
                        Icon(Icons.Default.AutoAwesome, null, tint = BrandBlue, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("AI Assistant", color = BrandBlue, fontSize = 13.sp)
                    }
                    Text("${message.length}/500", fontSize = 12.sp, color = TextSecondary)
                }
            }

            // Checkbox: Generate thumbnails from links
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = generateThumbnails,
                    onCheckedChange = { generateThumbnails = it }
                )
                Text("Generate thumbnails from links", fontSize = 14.sp, color = TextPrimary)
            }

            // Attachments Section
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Attachments", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { isAttachmentSheetOpen = true },
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(BrandBlue, RoundedCornerShape(8.dp))
                            ) {
                                Icon(Icons.Default.AttachFile, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            if (isImportingAttachment) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text(if (attachmentUri != null) "1/1" else "0/1", fontSize = 13.sp, color = TextSecondary)
                            }
                        }
                    }

                    // Attachment Preview Container
                    if (attachmentUri != null) {
                        Spacer(modifier = Modifier.height(14.dp))

                        if (attachmentType == "IMAGE") {
                            // Rich Image Preview with Edit & Delete Controls
                            val bitmap = remember(attachmentUri) {
                                try {
                                    val uri = Uri.parse(attachmentUri)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                        val source = ImageDecoder.createSource(context.contentResolver, uri)
                                        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                                            val maxDim = 800
                                            val width = info.size.width
                                            val height = info.size.height
                                            if (width > maxDim || height > maxDim) {
                                                val scale = maxDim.toFloat() / maxOf(width, height)
                                                decoder.setTargetSize((width * scale).toInt(), (height * scale).toInt())
                                            }
                                        }.asImageBitmap()
                                    } else {
                                        context.contentResolver.openInputStream(uri)?.use { stream ->
                                            BitmapFactory.decodeStream(stream)?.asImageBitmap()
                                        }
                                    }
                                } catch (e: Exception) {
                                    null
                                }
                            }

                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = BackgroundLight),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp)
                                ) {
                                    if (bitmap != null) {
                                        Image(
                                            bitmap = bitmap,
                                            contentDescription = "Selected Gallery Image",
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(12.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(BrandBlueLight),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.Image, null, tint = BrandBlue, modifier = Modifier.size(48.dp))
                                        }
                                    }

                                    // Top Action Buttons: Edit (Change) & Delete (Remove)
                                    Row(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        // Edit / Replace Image Button
                                        Surface(
                                            shape = CircleShape,
                                            color = Color.White.copy(alpha = 0.9f),
                                            shadowElevation = 3.dp,
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clickable { imageLauncher.launch("image/*") }
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(Icons.Default.Edit, contentDescription = "Edit Image", tint = BrandBlue, modifier = Modifier.size(18.dp))
                                            }
                                        }

                                        // Delete Image Button
                                        Surface(
                                            shape = CircleShape,
                                            color = Color.White.copy(alpha = 0.9f),
                                            shadowElevation = 3.dp,
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clickable {
                                                    attachmentUri = null
                                                    attachmentType = null
                                                    attachmentName = null
                                                    attachmentSize = null
                                                }
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete Image", tint = DangerRed, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }

                                    // Bottom Info Pill: Name and Size
                                    Surface(
                                        shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
                                        color = Color.Black.copy(alpha = 0.65f),
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = attachmentName ?: "Gallery Image",
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (!attachmentSize.isNullOrBlank()) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = attachmentSize!!,
                                                    color = Color.White.copy(alpha = 0.8f),
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // File / Document / Audio / Video Card
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = BrandBlueLight,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = when (attachmentType) {
                                                "DOCUMENT" -> Color(0xFF5F66CD)
                                                "AUDIO" -> Color(0xFFE66624)
                                                "VIDEO" -> Color(0xFFE91E63)
                                                else -> BrandBlue
                                            },
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = when (attachmentType) {
                                                        "DOCUMENT" -> Icons.Default.InsertDriveFile
                                                        "AUDIO" -> Icons.Default.Headphones
                                                        "VIDEO" -> Icons.Default.Videocam
                                                        else -> Icons.Default.AttachFile
                                                    },
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(10.dp))

                                        Column {
                                            Text(
                                                text = attachmentName ?: "File",
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 14.sp,
                                                color = TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${attachmentType ?: "File"} • ${attachmentSize ?: "Unknown size"}",
                                                fontSize = 12.sp,
                                                color = TextSecondary
                                            )
                                        }
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        // Edit / Replace Button
                                        IconButton(
                                            onClick = {
                                                when (attachmentType) {
                                                    "DOCUMENT" -> docLauncher.launch("*/*")
                                                    "AUDIO" -> audioLauncher.launch("audio/*")
                                                    "VIDEO" -> videoLauncher.launch("video/*")
                                                    else -> docLauncher.launch("*/*")
                                                }
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.Edit, contentDescription = "Edit File", tint = BrandBlue, modifier = Modifier.size(18.dp))
                                        }

                                        // Delete Button
                                        IconButton(
                                            onClick = {
                                                attachmentUri = null
                                                attachmentType = null
                                                attachmentName = null
                                                attachmentSize = null
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete File", tint = DangerRed, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Schedule Section (Date & Time)
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Schedule", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        IconButton(
                            onClick = {},
                            modifier = Modifier
                                .size(36.dp)
                                .background(BrandBlue, RoundedCornerShape(8.dp))
                        ) {
                            Icon(Icons.Default.CalendarMonth, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Date Box
                        OutlinedCard(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    val dateDialog = DatePickerDialog(
                                        context,
                                        { _, y, m, d ->
                                            calendar.set(Calendar.YEAR, y)
                                            calendar.set(Calendar.MONTH, m)
                                            calendar.set(Calendar.DAY_OF_MONTH, d)
                                            calendar.set(Calendar.SECOND, 30) // Strictly at 30 seconds
                                            calendar.set(Calendar.MILLISECOND, 0)
                                            scheduledMillis = calendar.timeInMillis
                                            displayDate = dateFormatter.format(Date(scheduledMillis))
                                        },
                                        calendar.get(Calendar.YEAR),
                                        calendar.get(Calendar.MONTH),
                                        calendar.get(Calendar.DAY_OF_MONTH)
                                    )
                                    dateDialog.show()
                                },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Date", fontSize = 11.sp, color = TextSecondary)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.CalendarToday, null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(displayDate, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                                }
                            }
                        }

                        // Time Box (Enforces 30-second target trigger)
                        OutlinedCard(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    val timeDialog = TimePickerDialog(
                                        context,
                                        { _, h, min ->
                                            calendar.set(Calendar.HOUR_OF_DAY, h)
                                            calendar.set(Calendar.MINUTE, min)
                                            calendar.set(Calendar.SECOND, 30) // Strictly at 30 seconds
                                            calendar.set(Calendar.MILLISECOND, 0)
                                            scheduledMillis = calendar.timeInMillis
                                            displayTime = timeFormatter.format(Date(scheduledMillis))
                                        },
                                        calendar.get(Calendar.HOUR_OF_DAY),
                                        calendar.get(Calendar.MINUTE),
                                        false
                                    )
                                    timeDialog.show()
                                },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Time", fontSize = 11.sp, color = TextSecondary)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.AccessTime, null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(displayTime, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Repeat Selector
                    Box {
                        OutlinedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isRepeatMenuOpen = true },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Repeat, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(repeatRule.displayName, fontSize = 14.sp, color = TextPrimary)
                                }
                                Icon(Icons.Default.ArrowDropDown, null, tint = TextSecondary)
                            }
                        }

                        DropdownMenu(
                            expanded = isRepeatMenuOpen,
                            onDismissRequest = { isRepeatMenuOpen = false }
                        ) {
                            RepeatRule.values().forEach { rule ->
                                DropdownMenuItem(
                                    text = { Text(rule.displayName) },
                                    onClick = {
                                        repeatRule = rule
                                        isRepeatMenuOpen = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBlueLight),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Icon(Icons.Outlined.AccessTime, null, tint = BrandBlue, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Check time conflict", color = BrandBlue, fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBlueLight),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Icon(Icons.Outlined.CalendarMonth, null, tint = BrandBlue, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("View Schedule Summary", color = BrandBlue, fontSize = 13.sp)
                    }
                }
            }

            // Ask me before sending switch
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Notifications, null, tint = BrandBlue, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Ask me before sending", fontSize = 14.sp, color = TextPrimary)
                    }
                    Switch(
                        checked = askBeforeSending,
                        onCheckedChange = { askBeforeSending = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = BrandBlue)
                    )
                }
            }

            // Retry on Failure / Network Issue
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Refresh, null, tint = BrandBlue, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Retry on Failure / Network Issue", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Box {
                        OutlinedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isRetryMenuOpen = true },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val retryText = when (retryIntervalMinutes) {
                                    null, 0 -> "Select retry interval (Off)"
                                    1 -> "Retry after 1 minute (Max 3 times)"
                                    2 -> "Retry after 2 minutes (Max 3 times)"
                                    3 -> "Retry after 3 minutes (Max 3 times)"
                                    5 -> "Retry after 5 minutes (Max 3 times)"
                                    10 -> "Retry after 10 minutes (Max 3 times)"
                                    else -> "Retry after $retryIntervalMinutes minutes (Max 3 times)"
                                }
                                Text(
                                    text = retryText,
                                    fontSize = 14.sp,
                                    color = if (retryIntervalMinutes != null && retryIntervalMinutes != 0) TextPrimary else TextSecondary
                                )
                                Icon(Icons.Default.ArrowDropDown, null, tint = TextSecondary)
                            }
                        }

                        DropdownMenu(
                            expanded = isRetryMenuOpen,
                            onDismissRequest = { isRetryMenuOpen = false }
                        ) {
                            listOf(
                                null to "Off (Don't retry)",
                                1 to "Retry after 1 minute (Max 3 times)",
                                2 to "Retry after 2 minutes (Max 3 times)",
                                3 to "Retry after 3 minutes (Max 3 times)",
                                5 to "Retry after 5 minutes (Max 3 times)",
                                10 to "Retry after 10 minutes (Max 3 times)"
                            ).forEach { (minutes, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        retryIntervalMinutes = minutes
                                        isRetryMenuOpen = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (retryIntervalMinutes != null && retryIntervalMinutes != 0)
                            "If mobile data is off or delivery fails, Postly will automatically retry up to 3 times before marking as Failed. Status stays Pending while retrying."
                        else
                            "Default: No retry. Select an interval if you want Postly to auto-retry when data is off.",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(60.dp))
        }
    }

    // WhatsApp-Style Attachments Modal BottomSheet
    if (isAttachmentSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { isAttachmentSheetOpen = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Add Attachment",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(20.dp))

                // WhatsApp-like grid: Gallery, Document, Video, Audio
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    // 1. Gallery (Images / Photos)
                    AttachmentOptionItem(
                        icon = Icons.Default.Image,
                        label = "Gallery",
                        color = Color(0xFFAC44CF),
                        onClick = {
                            isAttachmentSheetOpen = false
                            imageLauncher.launch("image/*")
                        }
                    )

                    // 2. Document (PDFs, Word, Files)
                    AttachmentOptionItem(
                        icon = Icons.Default.InsertDriveFile,
                        label = "Document",
                        color = Color(0xFF5F66CD),
                        onClick = {
                            isAttachmentSheetOpen = false
                            docLauncher.launch("*/*")
                        }
                    )

                    // 3. Audio (Music, Voice)
                    AttachmentOptionItem(
                        icon = Icons.Default.Headphones,
                        label = "Audio",
                        color = Color(0xFFE66624),
                        onClick = {
                            isAttachmentSheetOpen = false
                            audioLauncher.launch("audio/*")
                        }
                    )

                    // 4. Video (Videos from storage)
                    AttachmentOptionItem(
                        icon = Icons.Default.Videocam,
                        label = "Video",
                        color = Color(0xFFE91E63),
                        onClick = {
                            isAttachmentSheetOpen = false
                            videoLauncher.launch("video/*")
                        }
                    )
                }

                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    }

    // Manual Contact Dialog (Only if user chooses "Enter Manually")
    if (isManualContactDialogOpen) {
        var tempName by remember { mutableStateOf(recipientName) }
        var tempPhone by remember { mutableStateOf(recipientPhone) }

        AlertDialog(
            onDismissRequest = { isManualContactDialogOpen = false },
            title = { Text("Enter WhatsApp Contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = tempName,
                        onValueChange = { tempName = it },
                        label = { Text("Contact Name (e.g. Friends)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tempPhone,
                        onValueChange = { tempPhone = it },
                        label = { Text("Phone Number with Country Code (e.g. +919876543210)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        recipientName = tempName
                        recipientPhone = tempPhone
                        isManualContactDialogOpen = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
                ) {
                    Text("Done")
                }
            },
            dismissButton = {
                TextButton(onClick = { isManualContactDialogOpen = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun AttachmentOptionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { onClick() }
            .padding(8.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = color,
            modifier = Modifier.size(56.dp),
            shadowElevation = 2.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary
        )
    }
}
