package com.hospital.photolog

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.hospital.photolog.databinding.ActivityMainBinding
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private var scene = "盘库"
    private var quantity = 1
    private val photos = ArrayList<PhotoItem>()
    private lateinit var adapter: PhotoAdapter
    private val saveDir by lazy {
        File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "hospital")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = PhotoAdapter(photos) { pos ->
            photos[pos].file.delete()
            photos.removeAt(pos)
            adapter.notifyItemRemoved(pos)
            Toast.makeText(this, "已删除，剩余 ${photos.size} 张", Toast.LENGTH_SHORT).show()
        }
        binding.recyclerThumbs.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.recyclerThumbs.adapter = adapter

        // 场景切换（盘库 / 调拨）
        binding.toggleScene.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                scene = if (checkedId == R.id.btnSceneDiao) "调拨" else "盘库"
                updateWatermarkPreview()
            }
        }
        binding.toggleScene.check(R.id.btnScenePan)

        // 数量步进
        binding.btnQtyMinus.setOnClickListener { if (quantity > 1) { quantity--; updateQty() } }
        binding.btnQtyPlus.setOnClickListener { if (quantity < 999) { quantity++; updateQty() } }

        // 快门 / 分享 / 打包
        binding.fabShutter.setOnClickListener { takePhoto() }
        binding.btnShare.setOnClickListener { shareToWeChat() }
        binding.btnZip.setOnClickListener { exportZip() }

        updateWatermarkPreview()
        loadExistingPhotos()
        ensureCameraPermission()
    }

    private fun updateQty() {
        binding.tvQty.text = quantity.toString()
        updateWatermarkPreview()
    }

    private fun updateWatermarkPreview() {
        val t = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date())
        binding.watermarkPreview.text = "数量：$quantity\n时间：$t"
    }

    /** 启动时把磁盘上已有的照片载入列表（切应用/重开不丢，比网页 IndexedDB 更稳） */
    private fun loadExistingPhotos() {
        saveDir.mkdirs()
        val files = saveDir.listFiles { f -> f.name.startsWith("wm_") && f.name.endsWith(".jpg") }
        files?.sortedByDescending { it.name }?.forEach { photos.add(PhotoItem(it, "")) }
        adapter.notifyDataSetChanged()
    }

    private fun ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1001)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, "需要相机权限才能拍照", Toast.LENGTH_LONG).show()
        }
    }

    /** 用系统默认后置镜头（DEFAULT_BACK_CAMERA，不强行切前置），无网页黑屏/拦截 */
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
        imageCapture.takePicture(opts, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "拍照失败：${exc.message}", Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
                    val wm = Watermark.burn(raw, quantity, scene, time)
                    raw.delete()
                    photos.add(0, PhotoItem(wm, time))
                    adapter.notifyItemInserted(0)
                    binding.recyclerThumbs.scrollToPosition(0)
                    if (photos.size > 9) {
                        Toast.makeText(
                            this@MainActivity,
                            "已拍 ${photos.size} 张。超过9张建议用微信「收藏」一次性存",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(this@MainActivity, "已拍 ${photos.size} 张", Toast.LENGTH_SHORT).show()
                    }
                }
            })
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

    private fun exportZip() {
        if (photos.isEmpty()) {
            Toast.makeText(this, "还没有照片", Toast.LENGTH_SHORT).show()
            return
        }
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: cacheDir
        val zipFile = File(
            dir,
            "医院盘库_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(Date())}.zip"
        )
        try {
            val zos = java.util.zip.ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile)))
            zos.use {
                photos.forEach { p ->
                    zos.putNextEntry(java.util.zip.ZipEntry(p.file.name))
                    p.file.inputStream().use { src -> src.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "打包失败：${e.message}", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", zipFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "导出ZIP（可发微信/存网盘）"))
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
