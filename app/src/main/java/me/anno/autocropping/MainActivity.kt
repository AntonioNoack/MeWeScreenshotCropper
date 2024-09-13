package me.anno.autocropping

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.get
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.concurrent.thread
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var imagePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var intentSenderLauncher: ActivityResultLauncher<IntentSenderRequest>

    private var cropped: Bitmap? = null
    private var uri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val imageView = findViewById<ImageView>(R.id.imageView)

        // Initialize the launcher to handle image picking result
        imagePickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // Handle the image result
                val data: Intent? = result.data
                data?.data?.let { uri ->
                    // Get the image URI
                    val imagePath = getPathFromUri(uri)
                    imageView.setImageURI(uri)
                    thread(name = "decodingImage") {
                        try {
                            val bitmap = getBitmapFromUri(uri)
                            if (bitmap != null) {
                                val bounds = findBounds(bitmap)
                                println("cropped result: $bounds")
                                if (bounds.minX > 0 ||
                                    bounds.minY > 0 ||
                                    bounds.maxX < bitmap.width - 1 ||
                                    bounds.maxY < bitmap.height - 1
                                ) {
                                    val previous = cropped
                                    cropped = Bitmap.createBitmap(
                                        bitmap, bounds.minX, bounds.minY,
                                        bounds.maxX - bounds.minX + 1,
                                        bounds.maxY - bounds.minY + 1
                                    )
                                    runOnUiThread {
                                        imageView.setImageBitmap(cropped)
                                        previous?.recycle()
                                        this.uri = uri
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    Log.d("Image Path", "Selected image path: $imagePath")
                }
            }
        }

        intentSenderLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // User granted permission, now overwrite the image
                trySaveImage()
            }
        }

        findViewById<Button>(R.id.chooseImageButton)
            .setOnClickListener {
                openImagePicker()
            }

        findViewById<Button>(R.id.saveImageButton)
            .setOnClickListener { saveImage() }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun trySaveImage() {
        val bitmap = cropped ?: return
        val uri = uri ?: return
        contentResolver.openOutputStream(uri)?.use { outputStream ->
            val format =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
                else Bitmap.CompressFormat.JPEG
            bitmap.compress(format, 95, outputStream)
        }
        runOnUiThread {
            Toast.makeText(this, getString(R.string.saved), Toast.LENGTH_SHORT).show()
        }
        cropped = null
    }

    private fun saveImage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                trySaveImage()
            } catch (e: RecoverableSecurityException) {
                val intentSenderRequest = IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
                intentSenderLauncher.launch(intentSenderRequest)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            try {
                trySaveImage()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    data class Bounds(
        val minX: Int, val minY: Int,
        val maxX: Int, val maxY: Int
    )

    private fun findBounds(bitmap: Bitmap): Bounds {
        var bounds = Bounds(0, 0, bitmap.width - 1, bitmap.height - 1)
        while (true) {
            val bx = findBordersX(bitmap, bounds)
            println("$bounds -> $bx")
            val by = findBordersY(bitmap, bx)
            println("$bx -> $by")
            if (by == bounds) {
                return by
            } else bounds = by
        }
    }

    private fun findBordersX(bitmap: Bitmap, bounds: Bounds): Bounds {
        var min = bounds.minX
        var max = bounds.maxX
        min += findBorder(max - min, bounds.maxY - bounds.minY) { dx, dy ->
            bitmap[min + dx, bounds.minY + dy]
        }
        max -= findBorder(max - min, bounds.maxY - bounds.minY) { dx, dy ->
            bitmap[max - dx, bounds.minY + dy]
        }
        return Bounds(min, bounds.minY, max, bounds.maxY)
    }

    private fun findBordersY(bitmap: Bitmap, bounds: Bounds): Bounds {
        // find borders
        var min = bounds.minY
        var max = bounds.maxY
        min += findBorder(max - min, bounds.maxX - bounds.minX) { dy, dx ->
            bitmap[bounds.minX + dx, min + dy]
        }
        max -= findBorder(max - min, bounds.maxX - bounds.minX) { dy, dx ->
            bitmap[bounds.minX + dx, max - dy]
        }
        return Bounds(bounds.minX, min, bounds.maxX, max)
    }

    private fun interface ColorGetter {
        operator fun get(x: Int, y: Int): Int
    }

    private fun findBorder(scanSize: Int, secondarySize: Int, bitmap: ColorGetter): Int {
        val cropSize = findBorderSize(scanSize, secondarySize, bitmap)
        println("potential crop size: $cropSize / $scanSize")
        // check that all pixels are the same inside this block
        val color = bitmap[0, 0]
        for (x in 0 until cropSize) {
            for (y in 0 until secondarySize) {
                if (!isSameColor(color, bitmap[x, y])) {
                    println(
                        "cancelled crop on $x,$y, " +
                                "${color.toString(16)} != ${bitmap[x, y].toString(16)}"
                    )
                    return x
                }
            }
        }
        println("crop success")
        return cropSize
    }

    private fun findBorderSize(scanSize: Int, secondarySize: Int, bitmap: ColorGetter): Int {
        // go down left and right side
        if (isSameColor(bitmap[0, 0], bitmap[0, secondarySize])) {
            val color = bitmap[0, 0]
            for (i in 1..scanSize) {
                if (!isSameColor(color, bitmap[i, 0]) ||
                    !isSameColor(color, bitmap[i, secondarySize])
                ) return i
            }
            return 0 // full image??? -> just return 0 to save processing time
        } else return 0
    }

    private fun isSameColor(a: Int, b: Int): Boolean {
        val ra = a.and(0xff0000)
        val rb = b.and(0xff0000)
        val ga = a.and(0xff00)
        val gb = b.and(0xff00)
        val ba = a.and(0xff)
        val bb = b.and(0xff)
        return abs(ra - rb) < 0x50000 &&
                abs(ga - gb) < 0x500 &&
                abs(ba - bb) < 5
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_PICK)
        intent.type = "image/*"
        imagePickerLauncher.launch(intent)
    }

    private fun getPathFromUri(uri: Uri): String? {
        var path: String? = null
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            cursor.moveToFirst()
            path = cursor.getString(columnIndex)
        }
        return path
    }

    private fun getBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            // Open input stream and decode it to a Bitmap
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}