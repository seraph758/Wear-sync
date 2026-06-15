package de.rhaeus.wearsync

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import java.nio.charset.StandardCharsets

class PhoneSyncMainFragment : Fragment(), MessageClient.OnMessageReceivedListener {
    private val isNotificationAllowedState = mutableStateOf(false)
    private val isAccessibilityAllowedState = mutableStateOf(false)
    private val isCameraAllowedState = mutableStateOf(false) 
    
    // 🎯 新增：手錶端 Setting Write 權限狀態（預設 false）
    private val isWearSettingWriteAllowedState = mutableStateOf(false)
    
    private val isConnectedState = mutableStateOf(false)
    private var capabilityChangedListener: CapabilityClient.OnCapabilityChangedListener? = null
    private val UNIVERSAL_SYNC_PATH = "/wear-universal-sync"

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        isCameraAllowedState.value = isGranted
        if (isGranted) {
            Log.d("WearSync_Fragment", "📸 用戶已手動授予相機權限")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // [核心補償保險]：當 Fragment 的 UI 重新創建時，如果相機服務此時沒有運行，確保 Activity 裡的小窗預覽是隱藏的
        (activity as? PhoneSyncMainActivity)?.hideLocalPreview()

        // 註冊 Wear 訊息監聽器，用以接收手錶端回傳的權限狀態
        Wearable.getMessageClient(requireContext()).addListener(this)

        return ComposeView(requireContext()).apply {
            setContent {
                MaterialTheme(
                    colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        val isNotificationAllowed by isNotificationAllowedState
                        val isAccessibilityAllowed by isAccessibilityAllowedState
                        val isCameraAllowed by isCameraAllowedState
                        val isWearSettingWriteAllowed by isWearSettingWriteAllowedState
                        val isConnected by isConnectedState

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // 標題與連線狀態卡片
                            StatusCard(isConnected = isConnected)

                            // 頂部警告橫幅（如果未完全授權，包含手錶端權限未開啟時顯示）
                            AnimatedVisibility(visible = !isNotificationAllowed || !isAccessibilityAllowed || !isCameraAllowed || !isWearSettingWriteAllowed) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = "⚠️ 權限未完全啟用",
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "部分功能在未授權前將無法正常工作，請檢查下方各項開關。",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                            }

                            // 核心模塊一：通知與勿擾同步授權（DND 核心卡片）
                            PermissionCard(
                                title = "通知與勿擾同步授權",
                                description = "允許手機捕獲系統的勿擾模式(DND)變更，並與手錶端進行即時雙向狀態同步。",
                                isAllowed = isNotificationAllowed,
                                onClick = {
                                    startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                                }
                            )

                            // 核心模塊二：無障礙自動化服務（鬧鐘與睡眠核心卡片）
                            PermissionCard(
                                title = "無障礙自動化服務",
                                description = "用於自動化接管睡眠模式切換，以及無縫捕捉系統鬧鐘的響鈴與跳轉狀態。",
                                isAllowed = isAccessibilityAllowed,
                                onClick = {
                                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                }
                            )

                            // 核心模塊三：相機硬體功能授權（相機同步卡片）
                            PermissionCard(
                                title = "相機硬體功能授權",
                                description = "用於允許手錶端遠端控制手機鏡頭，並進行低延遲畫質分流傳輸。",
                                isAllowed = isCameraAllowed,
                                onClick = {
                                    if (!isCameraAllowed) {
                                        permissionLauncher.launch(android.Manifest.permission.CAMERA)
                                    }
                                }
                            )

                            // 🎯 核心模塊四：新增全新「手錶端修改系統設置權限 (Setting Write)」檢測卡片
                            PermissionCard(
                                title = "手錶端系統設置修改授權",
                                description = "用於同步更改手錶端的深度設置項目。若顯示未啟用，請點擊右下角向手錶發送引導開關請求。",
                                isAllowed = isWearSettingWriteAllowed,
                                onClick = {
                                    // 點擊後，發送遠端指令讓手錶跳轉到修改系統設置的授權頁面
                                    sendRemoteControlAction("REQUEST_WEAR_WRITE_SETTINGS_UI")
                                }
                            )

                            Spacer(modifier = Modifier.weight(1f))

                            // 底部項目版本提示信息
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "WearSync Project • 智慧互聯完全體",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun StatusCard(isConnected: Boolean) {
        val bgColor = if (isConnected) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
        val textColor = if (isConnected) Color(0xFF2E7D32) else Color(0xFFC62828)
        val statusText = if (isConnected) "手錶已連線 (DND & Camera Ready)" else "等待手錶端同步連線..."

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = bgColor)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "同步服務狀態",
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = statusText,
                        color = textColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    @Composable
    fun PermissionCard(
        title: String,
        description: String,
        isAllowed: Boolean,
        onClick: () -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (isAllowed) {
                        Text(
                            text = "已啟用",
                            color = Color(0xFF2E7D32),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Button(
                    onClick = onClick,
                    modifier = Modifier.align(Alignment.End),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isAllowed) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(text = if (isAllowed) "重新配置/遠端引導" else "去授權開關")
                }
            }
        }
    }

    // 🎯 監聽手錶端發送過來的權限狀態數據包
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (!UNIVERSAL_SYNC_PATH.equalsIgnoreCase(messageEvent.path)) return
        try {
            val jsonStr = String(messageEvent.data, StandardCharsets.UTF_8)
            val json = JSONObject(jsonStr)
            val type = json.optString("type", "")
            val action = json.optString("action", "")

            if ("wear_status_response".equals(type, ignoreCase = true) && "RESPONSE_WEAR_WRITE_SETTINGS_STATUS".equals(action, ignoreCase = true)) {
                val hasPermission = json.optBoolean("has_permission", false)
                // 回到主執行緒更新 Compose UI 狀態
                activity?.runOnUiThread {
                    isWearSettingWriteAllowedState.value = hasPermission
                    Log.d("WearSync_Fragment", "🔄 收到手錶端 Setting Write 權限反饋: $hasPermission")
                }
            }
        } catch (e: Exception) {
            Log.e("WearSync_Fragment", "解析手錶反饋狀態異常", e)
        }
    }

    override fun onResume() {
        super.onResume()
        checkNotificationPermission()
        checkAccessibilityPermission()
        isCameraAllowedState.value = requireContext().checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        registerConnectivityListener()
        
        // 🎯 每次回到手機介面時，主動發送請求向手錶查詢其 Setting Write 權限狀態
        sendRemoteControlAction("QUERY_WEAR_WRITE_SETTINGS_PERMISSION")
    }

    override fun onPause() { 
        super.onPause()
        unregisterConnectivityListener() 
    }

    override fun onDestroyView() {
        // 銷毀 UI 時註銷監聽器，防記憶體洩漏
        Wearable.getMessageClient(requireContext()).removeListener(this)
        super.onDestroyView()
    }

    private fun checkNotificationPermission() {
        val flat = Settings.Secure.getString(requireContext().contentResolver, "enabled_notification_listeners")
        isNotificationAllowedState.value = flat != null && flat.contains(requireContext().packageName)
    }

    private fun checkAccessibilityPermission() {
        var accessibilityEnabled = 0
        val service = requireContext().packageName + "/" + "de.rhaeus.wearsync.BedtimeAutomationService"
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                requireContext().contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } catch (e: Exception) {
            Log.e("WearSync_Fragment", "檢測無障礙服務異常", e)
        }
        val mStringColonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
        if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(
                requireContext().contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (settingValue != null) {
                mStringColonSplitter.setString(settingValue)
                while (mStringColonSplitter.hasNext()) {
                    val accessibilityService = mStringColonSplitter.next()
                    if (accessibilityService.equals(service, ignoreCase = true)) {
                        isAccessibilityAllowedState.value = true
                        return
                    }
                }
            }
        }
        isAccessibilityAllowedState.value = false
    }

    // 🎯 封裝一個通用的向手錶節點發送指令的輔助函數
    private fun sendRemoteControlAction(action: String) {
        Thread {
            try {
                val json = JSONObject().apply {
                    put("sender", "phone")
                    put("type", "setting_control")
                    put("action", action)
                }
                val data = json.toString().toByteArray(StandardCharsets.UTF_8)
                val context = context ?: return@Thread
                val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                for (node in nodes) {
                    Wearable.getMessageClient(context).sendMessage(node.id, UNIVERSAL_SYNC_PATH, data)
                }
                Log.d("WearSync_Fragment", "🚀 已向手錶端投遞指令: $action")
            } catch (e: Exception) {
                Log.e("WearSync_Fragment", "向手錶發送指令失敗: $action", e)
            }
        }.start()
    }

    private fun registerConnectivityListener() {
        val context = requireContext()
        Wearable.getCapabilityClient(context).getCapability("dnd_sync", CapabilityClient.FILTER_REACHABLE).addOnSuccessListener { isConnectedState.value = it.nodes.isNotEmpty() }
        capabilityChangedListener = CapabilityClient.OnCapabilityChangedListener { isConnectedState.value = it.nodes.isNotEmpty() }
        capabilityChangedListener?.let { Wearable.getCapabilityClient(context).addListener(it, "dnd_sync") }
    }

    private fun unregisterConnectivityListener() {
        capabilityChangedListener?.let { Wearable.getCapabilityClient(requireContext()).removeListener(it) }
    }
}
