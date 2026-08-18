package com.lst.bandtotp

import android.Manifest
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lst.bandtotp.model.TOTPInfo
import com.lst.bandtotp.parser.QRCodeDecoder
import com.lst.bandtotp.parser.TOTPParser
import com.lst.bandtotp.scanner.CameraScannerActivity
import com.lst.bandtotp.ui.theme.BandtotpTheme
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.node.Node
import com.xiaomi.xms.wearable.node.NodeApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray

class MainActivity : ComponentActivity() {

    private var nodeId: String? = null
    private var curNode: Node? = null
    private lateinit var nodeApi: NodeApi
    private val logTextState = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nodeApi = Wearable.getNodeApi(this)
        enableEdgeToEdge()
        setContent {
            BandtotpTheme {
                MainContent()
            }
        }
    }

    private fun openApp(onSuccess: () -> Unit = {}, onFailure: () -> Unit = {}) {
        val did = nodeId
        if (did == null) {
            log("未连接到穿戴设备")
            Toast.makeText(this, "未连接到穿戴设备", Toast.LENGTH_SHORT).show()
            onFailure()
            return
        }

        nodeApi.isWearAppInstalled(did)
            .addOnSuccessListener {
                nodeApi.launchWearApp(did, "pages/index")
                    .addOnSuccessListener {
                        log("已成功唤醒手环端 BandTOTP")
                        onSuccess()
                    }
                    .addOnFailureListener { e ->
                        log("唤醒手环端软件提示: ${e.message ?: "权限或设备状态受限"}")
                        onFailure()
                    }
            }
            .addOnFailureListener { e ->
                log("检测手环应用状态提示: ${e.message ?: "未安装或无法查询"}")
                onFailure()
            }
    }

    // 发送信息到手环
    private fun sendMessageToWearable(message: String, count: Int = 0) {
        val messageApi = Wearable.getMessageApi(this)
        val did = nodeId
        if (did != null) {
            val bytes = message.toByteArray()
            log("正在向手环发送数据包 (共 $count 个账号, 数据量: ${bytes.size} 字节)...")
            messageApi.sendMessage(did, bytes)
                .addOnSuccessListener {
                    log("同步数据已成功发送至手环！")
                    Toast.makeText(this, "数据已发送至手环！若未显示请在手环打开应用", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e ->
                    log("发送数据失败: ${e.message}")
                    Toast.makeText(this, "同步失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } else {
            Toast.makeText(this, "未连接到穿戴设备", Toast.LENGTH_SHORT).show()
            log("发送失败: 未连接到穿戴设备")
        }
    }

    // 查询已连接的设备
    private fun queryConnectedDevices(onDeviceFound: (String) -> Unit) {
        nodeApi.connectedNodes.addOnSuccessListener { nodes ->
            if (nodes.isNotEmpty()) {
                curNode = nodes[0]
                nodeId = nodes[0].id
                val devName = nodes[0].name
                onDeviceFound(devName)
                log("已连接设备: $devName (ID: ${nodes[0].id})")
                checkAndRequestPermissions()
            }
        }.addOnFailureListener {
            // ignore failure
        }
    }

    // 申请权限
    private fun checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH), 1001)
        }
        val authApi = Wearable.getAuthApi(this)
        val did = nodeId
        if (did != null) {
            authApi.checkPermission(did, Permission.DEVICE_MANAGER)
                .addOnSuccessListener { granted ->
                    if (!granted) {
                        authApi.requestPermission(did, Permission.DEVICE_MANAGER)
                            .addOnSuccessListener {
                                log("穿戴设备管理权限已获取")
                            }.addOnFailureListener { e ->
                                log("申请设备权限提示: ${e.message}")
                            }
                    } else {
                        log("穿戴设备权限已就绪")
                    }
                }.addOnFailureListener { e ->
                    // 未使用正式证书签名时返回 fingerprint verify failed，不影响手动在手环端打开应用同步
                    log("设备权限校验提示: ${e.message} (开发证书模式，手环端可手动打开应用同步)")
                }
        }
    }

    private fun log(message: Any) {
        logTextState.value += "$message\n"
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainContent(modifier: Modifier = Modifier) {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        var connectedDeviceText by remember { mutableStateOf("设备未连接") }
        var isConnected by remember { mutableStateOf(false) }
        var logText by remember { logTextState }

        // TOTP 账号列表与选中状态
        val accountList = remember { mutableStateListOf<TOTPInfo>() }
        val selectedSet = remember { mutableStateMapOf<Int, Boolean>() }

        // 手动输入弹窗状态
        var showManualInputDialog by remember { mutableStateOf(false) }
        var manualInputText by remember { mutableStateOf("") }

        // 编辑账号弹窗状态
        var editingAccountIndex by remember { mutableStateOf<Int?>(null) }
        var editIssuerName by remember { mutableStateOf("") }
        var editAccountName by remember { mutableStateOf("") }

        // 拖动排序状态
        var draggedIndex by remember { mutableStateOf<Int?>(null) }
        var dragOffset by remember { mutableStateOf(0f) }
        val itemHeights = remember { mutableStateMapOf<Int, Int>() }

        fun swapItems(i: Int, j: Int) {
            if (i in accountList.indices && j in accountList.indices && i != j) {
                val temp = accountList[i]
                accountList[i] = accountList[j]
                accountList[j] = temp

                val selI = selectedSet[i] == true
                val selJ = selectedSet[j] == true
                selectedSet[i] = selJ
                selectedSet[j] = selI
            }
        }

        // 日志展开/收起
        var isLogsExpanded by remember { mutableStateOf(false) }

        // 添加新解析出的账号
        fun addParsedAccounts(newItems: List<TOTPInfo>, sourceName: String) {
            if (newItems.isEmpty()) {
                Toast.makeText(context, "未识别到有效的两步验证账号", Toast.LENGTH_SHORT).show()
                log("[$sourceName] 未解析到有效账号")
                return
            }

            var addedCount = 0
            for (item in newItems) {
                // 查重：同一个 issuer + user + key
                val exists = accountList.any {
                    it.name.equals(item.name, ignoreCase = true) &&
                            it.usr.equals(item.usr, ignoreCase = true) &&
                            it.key.equals(item.key, ignoreCase = true)
                }
                if (!exists) {
                    val newIndex = accountList.size
                    accountList.add(item)
                    selectedSet[newIndex] = true
                    addedCount++
                }
            }

            log("[$sourceName] 成功导入 $addedCount 个新账号 (总共 ${accountList.size} 个)")
            Toast.makeText(context, "成功导入 $addedCount 个账号", Toast.LENGTH_SHORT).show()
        }

        // 1. 相机扫码启动器
        val scanLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val rawText = result.data?.getStringExtra(CameraScannerActivity.EXTRA_SCAN_RESULT)
                if (!rawText.isNullOrEmpty()) {
                    val parsed = TOTPParser.parse(rawText)
                    addParsedAccounts(parsed, "相机扫码")
                }
            }
        }

        // 2. 相册选图启动器
        val pickImageLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let {
                val decodedText = QRCodeDecoder.decodeFromUri(context, it)
                if (decodedText != null) {
                    val parsed = TOTPParser.parse(decodedText)
                    addParsedAccounts(parsed, "相册图片")
                } else {
                    Toast.makeText(context, "未能从图片识别出有效二维码", Toast.LENGTH_SHORT).show()
                    log("相册图片解析失败: 未识别到二维码")
                }
            }
        }

        // 3. 文件导入启动器
        val pickFileLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let {
                try {
                    context.contentResolver.openInputStream(it)?.use { stream ->
                        val parsed = TOTPParser.parseStream(stream)
                        addParsedAccounts(parsed, "文件导入")
                    }
                } catch (e: Exception) {
                    log("读取文件失败: ${e.message}")
                    Toast.makeText(context, "读取文件失败", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 同步至手环
        fun syncToBand() {
            if (!isConnected) {
                Toast.makeText(context, "请先等待手环连接", Toast.LENGTH_SHORT).show()
                return
            }

            val selectedItems = accountList.filterIndexed { index, _ ->
                selectedSet[index] == true
            }

            if (selectedItems.isEmpty()) {
                Toast.makeText(context, "请至少勾选一个要同步的账号", Toast.LENGTH_SHORT).show()
                return
            }

            val jsonArray = JSONArray()
            for (item in selectedItems) {
                jsonArray.put(item.toJson())
            }
            val payload = "{\"list\":$jsonArray}"

            log("开始同步 ${selectedItems.size} 个账号至手环...")
            openApp(
                onSuccess = {
                    // 手环端软件已成功拉起，等待 500ms 让手环端页面完成初始化并绑定互联监听
                    coroutineScope.launch {
                        delay(500)
                        sendMessageToWearable(payload, selectedItems.size)
                    }
                },
                onFailure = {
                    // 自动唤醒手环失败（受手环息屏或证书签名权限限制），直接发送数据包
                    log("提示: 未能自动拉起手环端软件，正在直接发送数据至手环...")
                    sendMessageToWearable(payload, selectedItems.size)
                }
            )
        }

        // 定时检查设备连接
        LaunchedEffect(Unit) {
            while (nodeId == null) {
                queryConnectedDevices { devName ->
                    connectedDeviceText = "设备: $devName"
                    isConnected = true
                }
                delay(1000)
            }
        }

        Scaffold(
            bottomBar = {
                // 底部同步按钮
                Surface(
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    val selectedCount = accountList.filterIndexed { idx, _ -> selectedSet[idx] == true }.size
                    Button(
                        onClick = { syncToBand() },
                        enabled = accountList.isNotEmpty() && selectedCount > 0,
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(16.dp)
                            .height(54.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (selectedCount > 0) "同步 $selectedCount 个账号至手环" else "请选择要同步的账号",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. 设备连接状态卡片
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isConnected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Watch,
                                contentDescription = null,
                                tint = Color.White
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isConnected) connectedDeviceText else "正在搜索小米手环 / 穿戴设备...",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isConnected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (isConnected) "已建立连接，可随时同步两步验证数据" else "请确保手环蓝牙已开启并与手机配对",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isConnected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                    }
                }

                // 2. 导入途径快捷网格
                Text(
                    text = "导入两步验证数据",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ImportActionButton(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.QrCodeScanner,
                        title = "相机扫码",
                        subtitle = "Google/微软/Steam",
                        onClick = {
                            val intent = Intent(context, CameraScannerActivity::class.java)
                            scanLauncher.launch(intent)
                        }
                    )

                    ImportActionButton(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.PhotoLibrary,
                        title = "相册选图",
                        subtitle = "二维码截图识别",
                        onClick = {
                            pickImageLauncher.launch("image/*")
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ImportActionButton(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.FolderOpen,
                        title = "文件导入",
                        subtitle = ".maFile / json / csv",
                        onClick = {
                            pickFileLauncher.launch("*/*")
                        }
                    )

                    ImportActionButton(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.ContentPaste,
                        title = "剪贴板/手动",
                        subtitle = "粘贴迁移码或JSON",
                        onClick = {
                            // 尝试直接读取剪贴板
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            if (clipboard != null && clipboard.hasPrimaryClip()) {
                                val clipData = clipboard.primaryClip
                                if (clipData != null && clipData.itemCount > 0) {
                                    val text = clipData.getItemAt(0).text?.toString() ?: ""
                                    if (text.isNotBlank()) {
                                        manualInputText = text
                                    }
                                }
                            }
                            showManualInputDialog = true
                        }
                    )
                }

                // 3. 待同步账号列表卡片
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "待同步账号",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                                    Text(
                                        text = "${accountList.size}",
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }

                            if (accountList.isNotEmpty()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(
                                        onClick = {
                                            val allSelected = accountList.indices.all { selectedSet[it] == true }
                                            accountList.indices.forEach { selectedSet[it] = !allSelected }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(if (accountList.indices.all { selectedSet[it] == true }) "反选" else "全选")
                                    }

                                    TextButton(
                                        onClick = {
                                            accountList.clear()
                                            selectedSet.clear()
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text("清空", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (accountList.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 28.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.Key,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                                        modifier = Modifier.size(44.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "暂无待同步账号",
                                        color = MaterialTheme.colorScheme.outline,
                                        fontSize = 14.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "请通过上方按钮扫描二维码或导入备份文件",
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                accountList.forEachIndexed { index, item ->
                                    val isDragging = draggedIndex == index
                                    AccountItemRow(
                                        item = item,
                                        isSelected = selectedSet[index] == true,
                                        isDragging = isDragging,
                                        dragOffset = if (isDragging) dragOffset else 0f,
                                        onCheckedChange = { checked ->
                                            selectedSet[index] = checked
                                        },
                                        onEdit = {
                                            editingAccountIndex = index
                                            editIssuerName = item.name
                                            editAccountName = item.usr
                                        },
                                        onDelete = {
                                            accountList.removeAt(index)
                                            // 重新排列 selection
                                            val newMap = mutableMapOf<Int, Boolean>()
                                            accountList.indices.forEach { idx ->
                                                val oldIdx = if (idx >= index) idx + 1 else idx
                                                newMap[idx] = selectedSet[oldIdx] == true
                                            }
                                            selectedSet.clear()
                                            selectedSet.putAll(newMap)
                                        },
                                        dragHandleModifier = Modifier.pointerInput(item, index) {
                                            detectDragGestures(
                                                onDragStart = {
                                                    draggedIndex = index
                                                    dragOffset = 0f
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    dragOffset += dragAmount.y
                                                    while (true) {
                                                        val current = draggedIndex ?: break
                                                        if (dragOffset > 0 && current < accountList.size - 1) {
                                                            val targetHeight = itemHeights[current + 1]?.toFloat() ?: 180f
                                                            if (dragOffset > targetHeight * 0.5f) {
                                                                swapItems(current, current + 1)
                                                                draggedIndex = current + 1
                                                                dragOffset -= targetHeight
                                                                continue
                                                            }
                                                        } else if (dragOffset < 0 && current > 0) {
                                                            val targetHeight = itemHeights[current - 1]?.toFloat() ?: 180f
                                                            if (dragOffset < -targetHeight * 0.5f) {
                                                                swapItems(current, current - 1)
                                                                draggedIndex = current - 1
                                                                dragOffset += targetHeight
                                                                continue
                                                            }
                                                        }
                                                        break
                                                    }
                                                },
                                                onDragEnd = {
                                                    draggedIndex = null
                                                    dragOffset = 0f
                                                },
                                                onDragCancel = {
                                                    draggedIndex = null
                                                    dragOffset = 0f
                                                }
                                            )
                                        },
                                        modifier = Modifier.onGloballyPositioned { coordinates ->
                                            itemHeights[index] = coordinates.size.height
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // 4. 日志卡片 (可折叠)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isLogsExpanded = !isLogsExpanded },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "运行日志",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Icon(
                                imageVector = if (isLogsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null
                            )
                        }

                        AnimatedVisibility(visible = isLogsExpanded) {
                            Column(modifier = Modifier.padding(top = 10.dp)) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(160.dp)
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                            RoundedCornerShape(8.dp)
                                        )
                                        .padding(10.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    Text(
                                        text = logText.ifEmpty { "暂无日志" },
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = { logTextState.value = "" }) {
                                        Text("清空日志", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                // 5. 关于卡片
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "关于 BandTOTP",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = "v2.0",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "BandTOTP 是一款支持将两步验证 (TOTP) 账号快速同步至小米手环及穿戴设备的开源助手。\n支持 Google Authenticator、Microsoft Authenticator、Watt Toolkit (Steam++)、Aegis、2FAS 等多平台导出格式。",
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Copyright (c) 2024-2026 leset0ng, Cathgao",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }

        // 手动输入/剪贴板粘贴弹窗
        if (showManualInputDialog) {
            AlertDialog(
                onDismissRequest = { showManualInputDialog = false },
                title = { Text("手动输入 / 粘贴数据") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "支持粘贴 Google 转移链接、otpauth:// 链接、Watt Toolkit JSON 或纯 Base32 密钥：",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = manualInputText,
                            onValueChange = { manualInputText = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            placeholder = { Text("在此粘贴或输入内容...") },
                            maxLines = 8
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (manualInputText.isNotBlank()) {
                                val parsed = TOTPParser.parse(manualInputText)
                                addParsedAccounts(parsed, "手动输入")
                                showManualInputDialog = false
                                manualInputText = ""
                            }
                        }
                    ) {
                        Text("解析导入")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showManualInputDialog = false }) {
                        Text("取消")
                    }
                }
            )
        }

        // 修改账号弹窗
        if (editingAccountIndex != null) {
            val currentIndex = editingAccountIndex!!
            val currentItem = accountList.getOrNull(currentIndex)
            if (currentItem != null) {
                AlertDialog(
                    onDismissRequest = { editingAccountIndex = null },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    title = { Text("修改账号信息") },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = editIssuerName,
                                onValueChange = { editIssuerName = it },
                                label = { Text("厂商名称 (Issuer)") },
                                placeholder = { Text("如: Google, Steam, GitHub") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = editAccountName,
                                onValueChange = { editAccountName = it },
                                label = { Text("账号名 / 用户名") },
                                placeholder = { Text("如: user@example.com") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            // 显示密钥与参数提示 (只读信息)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "参数: ${currentItem.digits}位 / ${currentItem.period}s / ${currentItem.algorithm}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    val maskedKey = if (currentItem.key.length > 8) {
                                        currentItem.key.take(4) + "••••" + currentItem.key.takeLast(4)
                                    } else currentItem.key
                                    Text(
                                        text = "密钥: $maskedKey",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val newName = editIssuerName.trim()
                                val newUsr = editAccountName.trim()
                                if (newName.isBlank() && newUsr.isBlank()) {
                                    Toast.makeText(context, "厂商名称和账号名不能同时为空", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                accountList[currentIndex] = currentItem.copy(
                                    name = if (newName.isNotBlank()) newName else "未知",
                                    usr = newUsr
                                )
                                editingAccountIndex = null
                            }
                        ) {
                            Text("保存")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { editingAccountIndex = null }) {
                            Text("取消")
                        }
                    }
                )
            } else {
                editingAccountIndex = null
            }
        }
    }

    @Composable
    fun ImportActionButton(
        modifier: Modifier = Modifier,
        icon: ImageVector,
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ) {
        Card(
            modifier = modifier
                .height(84.dp)
                .clickable { onClick() },
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    @Composable
    fun AccountItemRow(
        modifier: Modifier = Modifier,
        item: TOTPInfo,
        isSelected: Boolean,
        isDragging: Boolean = false,
        dragOffset: Float = 0f,
        dragHandleModifier: Modifier = Modifier,
        onCheckedChange: (Boolean) -> Unit,
        onEdit: () -> Unit,
        onDelete: () -> Unit
    ) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .zIndex(if (isDragging) 10f else 0f)
                .graphicsLayer {
                    translationY = if (isDragging) dragOffset else 0f
                    shadowElevation = if (isDragging) 16f else 0f
                    scaleX = if (isDragging) 1.02f else 1f
                    scaleY = if (isDragging) 1.02f else 1f
                }
                .clickable { onEdit() },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isDragging) MaterialTheme.colorScheme.surfaceVariant
                else if (isSelected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(if (isDragging) 8.dp else 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 拖动排序图标
                Box(
                    modifier = dragHandleModifier
                        .size(36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DragHandle,
                        contentDescription = "拖动排序",
                        tint = if (isDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Checkbox(
                    checked = isSelected,
                    onCheckedChange = onCheckedChange
                )

                Spacer(modifier = Modifier.width(4.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = if (item.usr.isNotBlank()) item.usr else "（无账号名）",
                        fontSize = 13.sp,
                        color = if (item.usr.isNotBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // 位数与周期（独立一行，位于密钥上方）
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = "${item.digits}位 / ${item.period}s",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Secret preview
                    val maskedSecret = if (item.key.length > 8) {
                        item.key.take(4) + "••••" + item.key.takeLast(4)
                    } else item.key

                    Text(
                        text = "密钥: $maskedSecret (${item.algorithm})",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "修改",
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
