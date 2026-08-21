package com.hospital.photolog

import android.Manifest
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.annotation.SuppressLint
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hospital.photolog.databinding.ActivityMainBinding
import android.content.ContentValues
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private var quantity = 1
    private val photos = ArrayList<PhotoItem>()
    private lateinit var adapter: PhotoAdapter
    private val saveDir by lazy {
        File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "hospital")
    }
    /** 写入手机「下载」目录下的独立文件夹（用户可在文件管理器/微信里直接找到） */
    private val DOWNLOAD_FOLDER = "HospitalPhotoLog"
    private var previewPos = -1
    /** 水印烧录较重，放到后台线程执行，避免快门卡顿 */
    private val workExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 透明状态栏 + 白色状态栏文字（预览铺满，控件已被根布局 fitsSystemWindows 顶到状态栏下方）
        window.statusBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
        }

        adapter = PhotoAdapter(
            photos,
            onOpen = { pos -> openPreview(pos) },
            onRemove = { pos -> removePhoto(pos) }
        )
        binding.recyclerThumbs.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.recyclerThumbs.adapter = adapter

        // 数量步进（底部，拇指可达）；修复：同步刷新大号数字
        binding.btnQtyMinus.setOnClickListener { if (quantity > 1) { quantity--; updateLive() } }
        binding.btnQtyPlus.setOnClickListener { if (quantity < 999) { quantity++; updateLive() } }

        // 快门 / 分享 / 保存并清空缓存 / 打开下载文件夹
        binding.fabShutter.setOnClickListener { takePhoto() }
        binding.btnShare.setOnClickListener { shareToWeChat() }
        binding.btnSave.setOnClickListener { saveAndClear() }
        binding.btnOpenDownloads.setOnClickListener { openDownloads() }

        // 全屏预览：关闭 / 删除
        binding.btnClosePreview.setOnClickListener { binding.previewOverlay.visibility = View.GONE }
        binding.btnDeletePreview.setOnClickListener {
            if (previewPos >= 0) removePhoto(previewPos)
            binding.previewOverlay.visibility = View.GONE
        }

        updateLive()
        loadExistingPhotos()
        ensureCameraPermission()
    }

    /** 同步刷新：底部大号数字 + 左上角仿水印的数量/时间标注 */
    private fun updateLive() {
        binding.tvQty.text = quantity.toString()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
        binding.tvHudQty.text = "数量：$quantity"
        binding.tvHudTime.text = "时间：$now"
    }

    private fun openPreview(pos: Int) {
        if (pos < 0 || pos >= photos.size) return
        previewPos = pos
        val bmp = BitmapFactory.decodeFile(photos[pos].file.absolutePath)
        binding.imgPreview.setImageBitmap(bmp)
        binding.previewOverlay.visibility = View.VISIBLE
    }

    private fun removePhoto(pos: Int) {
        if (pos < 0 || pos >= photos.size) return
        photos[pos].file.delete()
        photos.removeAt(pos)
        adapter.notifyItemRemoved(pos)
        adapter.notifyItemRangeChanged(pos, photos.size)
    }

    /** 启动时把磁盘上已有的照片载入列表（切应用/重开不丢） */
    private fun loadExistingPhotos() {
        saveDir.mkdirs()
        val files = saveDir.listFiles { f -> f.name.endsWith(".jpg") && !f.name.startsWith("raw_") }
        files?.sortedByDescending { it.name }?.forEach { photos.add(PhotoItem(it, "", 1, it.name)) }
        adapter.notifyDataSetChanged()
    }

    /** 相机权限检测：未授权则弹框说明，引导授权 */
    private fun ensureCameraPermission() {
        if (hasCameraPermission()) {
            startCamera()
        } else {
            showCameraRequestDialog()
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED

    /** 未授权时先弹自定义说明框，点「授权」再调系统权限请求 */
    private fun showCameraRequestDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("需要相机权限")
            .setMessage("拍照需要访问相机，请允许授权后使用。")
            .setCancelable(false)
            .setPositiveButton("授权") { _, _ -> requestCamera() }
            .setNegativeButton("暂不", null)
            .show()
    }

    private fun requestCamera() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1001)
    }

    /** 授权被拒后的弹框：可再请求则重试；已勾选「不再询问」则引导去系统设置 */
    private fun showCameraDeniedDialog() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("需要相机权限")
                .setMessage("未授权将无法拍照，是否重新授权？")
                .setCancelable(false)
                .setPositiveButton("重试") { _, _ -> requestCamera() }
                .setNegativeButton("取消", null)
                .show()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle("相机权限已关闭")
                .setMessage("请在系统设置中开启相机权限后重试。")
                .setCancelable(false)
                .setPositiveButton("去设置") { _, _ -> openAppSettings() }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                showCameraDeniedDialog()
            }
        } else if (requestCode == 1002) {
            // 老版本存储权限（仅 Android 9 及以下会请求）；授权后用户可点「保存」重试
        }
    }

    /** 系统默认后置镜头（DEFAULT_BACK_CAMERA，不强行切前置），无网页黑屏/拦截 */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            this.imageCapture = imageCapture
            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: run {
            Toast.makeText(this, "相机未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        saveDir.mkdirs()
        val raw = File(saveDir, "raw_${System.currentTimeMillis()}.jpg")
        val opts = ImageCapture.OutputFileOptions.Builder(raw).build()
        // 回调跑在主线程：立刻给快门反馈，重的水印烧录丢到后台，避免卡顿
        imageCapture.takePicture(opts, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "拍照失败：${exc.message}", Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    onCapturedFeedback() // 白闪 + 振动，先响应用户
                    val q = quantity
                    val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
                    val name = makeDownloadName(q)
                    workExecutor.execute {
                        val wm = Watermark.burn(raw, q, time, name)
                        raw.delete()
                        val item = PhotoItem(wm, time, q, name)
                        runOnUiThread {
                            photos.add(0, item)
                            adapter.notifyItemInserted(0)
                            binding.recyclerThumbs.scrollToPosition(0)
                        }
                    }
                }
            })
    }

    /** 拍照反馈：白闪 + 轻振动 + 新缩略图脉冲（不再用 toast 打扰） */
    private fun onCapturedFeedback() {
        // 白闪
        binding.flash.alpha = 0.85f
        binding.flash.animate().alpha(0f).setDuration(260).start()
        // 轻振动
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") v.vibrate(40)
            }
        } catch (_: Exception) { /* 部分设备无振动器，忽略 */ }
        // 新缩略图脉冲
        binding.recyclerThumbs.post {
            val vh = binding.recyclerThumbs.findViewHolderForAdapterPosition(0)
            vh?.itemView?.animate()?.scaleX(1.18f)?.scaleY(1.18f)?.setDuration(120)
                ?.withEndAction {
                    vh.itemView.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }?.start()
        }
    }

    private fun shareToWeChat() {
        if (photos.isEmpty()) {
            Toast.makeText(this, "还没有照片", Toast.LENGTH_SHORT).show()
            return
        }
        val uris = ArrayList<Uri>()
        photos.forEach { uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", it.file)) }
        val intent = Intent().apply {
            action = Intent.ACTION_SEND_MULTIPLE
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val wechat = packageManager.resolveActivity(
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                `package` = "com.tencent.mm"
            }, 0
        )
        if (wechat != null) {
            intent.setPackage("com.tencent.mm")
            startActivity(intent)
        } else {
            startActivity(Intent.createChooser(intent, "分享到微信/其他"))
        }
    }

    /** 点「保存」：全部导出到「下载 / HospitalPhotoLog」，然后清空工作缓存 */
    private fun saveAndClear() {
        if (photos.isEmpty()) {
            Toast.makeText(this, "还没有照片", Toast.LENGTH_SHORT).show()
            return
        }
        var ok = 0
        photos.forEach { item ->
            val name = item.downloadName ?: makeDownloadName(item.quantity)
            if (saveToDownload(item.file, name)) ok++
        }
        // 清空当前缓存的照片（工作副本删除 + 列表清空）
        photos.forEach { it.file.delete() }
        photos.clear()
        adapter.notifyDataSetChanged()
        Toast.makeText(
            this,
            "已保存 $ok 张到 下载/$DOWNLOAD_FOLDER，缓存已清空",
            Toast.LENGTH_LONG
        ).show()
    }

    /** 打开系统文件管理器并跳到「下载 / HospitalPhotoLog」；各厂商文件管理器兼容不一，失败则回退到系统「下载」 */
    private fun openDownloads() {
        val folderUri = Uri.parse(
            "content://com.android.externalstorage.documents/document/primary:Download/HospitalPhotoLog"
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(folderUri, "vnd.android.document/directory")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
            } catch (e2: Exception) {
                Toast.makeText(this, "未找到可用的文件管理器", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 写一张图到「下载 / HospitalPhotoLog」：Android 10+ 走 MediaStore，老版本走文件兼容 */
    private fun saveToDownload(src: File, displayName: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(src, displayName)
        } else {
            saveViaLegacyFile(src, displayName)
        }
    }

    @SuppressLint("NewApi")
    private fun saveViaMediaStore(src: File, displayName: String): Boolean {
        val resolver = contentResolver
        val folder = Environment.DIRECTORY_DOWNLOADS + File.separator + DOWNLOAD_FOLDER
        if (downloadEntryExists(displayName, folder)) return true
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: throw RuntimeException("openOutputStream null")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    @SuppressLint("NewApi")
    private fun downloadEntryExists(name: String, folder: String): Boolean {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val sel = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val args = arrayOf(name, folder)
        contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, sel, args, null
        )?.use { c -> return c.count > 0 }
        return false
    }

    /** 仅 Android 9 及以下会走到：需要 WRITE_EXTERNAL_STORAGE */
    private fun saveViaLegacyFile(src: File, displayName: String): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1002
            )
            return false
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DOWNLOAD_FOLDER
        )
        if (!dir.exists() && !dir.mkdirs()) return false
        val dst = File(dir, displayName)
        if (dst.exists()) return true
        return try {
            src.inputStream().use { inp -> dst.outputStream().use { inp.copyTo(it) } }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 下载目录里的文件名：时间_序号_数量.jpg（唯一，便于去重与人工辨认） */
    private fun makeDownloadName(quantity: Int): String {
        val ts = System.currentTimeMillis()
        val tf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date(ts))
        val uniq = ts % 1000
        return "${tf}_${String.format("%03d", uniq)}_数量${quantity}.jpg"
    }

    /** 音量键当快门（现场戴手套/拿货时比戳屏幕顺手） */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            takePhoto()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
