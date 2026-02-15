package io.github.robertobartola.mybackrec

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import io.github.robertobartola.mybackrec.R
import io.github.robertobartola.mybackrec.RecordService
import java.io.File

class MainActivity : AppCompatActivity() {

    private var recordService: RecordService? = null
    private var isBound = false
    private val PICK_FOLDER_REQUEST_CODE = 1002

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RecordService.LocalBinder
            recordService = binder.getService()
            isBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            recordService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val inputSecondi = findViewById<EditText>(R.id.editSeconds)
        val tastoStart = findViewById<Button>(R.id.btnStart)
        val tastoSave = findViewById<Button>(R.id.btnSave)
        val tastoStop = findViewById<Button>(R.id.btnStop)
        val tastoShare = findViewById<Button>(R.id.btnShare)

        val mainView = findViewById<View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        tastoStart.setOnClickListener {
            if (checkPermissions()) {
                val secondi = inputSecondi.text.toString().toIntOrNull() ?: 0
                if (secondi > 0) {
                    val intent = Intent(this, RecordService::class.java)
                    startForegroundService(intent)
                    bindService(intent, connection, BIND_AUTO_CREATE)

                    mainView.postDelayed({
                        recordService?.startRecording(secondi)
                        tastoStart.text = getString(R.string.listening)
                        tastoStart.isEnabled = false
                    }, 200)
                } else {
                    Toast.makeText(this, getString(R.string.insert_duration), Toast.LENGTH_SHORT).show()
                }
            }
        }

        tastoSave.setOnClickListener {
            if (isBound) {
                recordService?.freezeAndSave()
                Toast.makeText(this, getString(R.string.save_complete), Toast.LENGTH_SHORT).show()
            }
        }

        tastoStop.setOnClickListener {
            if (isBound) {
                recordService?.stopRecording()
                unbindService(connection)
                isBound = false
                tastoStart.text = getString(R.string.btn_start)
                tastoStart.isEnabled = true
            }
        }

        tastoShare.setOnClickListener {
            val directory = getExternalFilesDir(null)
            val files = directory?.listFiles { file -> file.extension == "wav" }
                ?.sortedByDescending { it.lastModified() }

            if (!files.isNullOrEmpty()) {
                val fileNames = files.map { it.name }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.share_title))
                    .setItems(fileNames) { _, which ->
                        mostraOpzioniFile(files[which])
                    }
// ... dentro onCreate, nel tastoShare.setOnClickListener ...
                    .setNeutralButton("Esporta tutto") { _, _ ->
                        selezionaCartellaDestinazione()
                    }
                    .setPositiveButton("Svuota tutto") { _, _ ->
                        AlertDialog.Builder(this)
                            .setTitle("Attenzione")
                            .setMessage("Vuoi davvero eliminare TUTTI i file salvati?")
                            .setPositiveButton("Elimina") { _, _ ->
                                val directory = getExternalFilesDir(null)
                                directory?.listFiles()?.forEach { it.delete() }
                                Toast.makeText(this, "Tutto eliminato", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("Annulla", null)
                            .show()
                    }
// .setNegativeButton("Annulla", null)...

                    .setNegativeButton("Annulla", null)
                    .show()
            } else {
                Toast.makeText(this, getString(R.string.no_file), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun mostraOpzioniFile(file: File) {
        val opzioni = arrayOf("Condividi", "Condividi ed Elimina", "Elimina")
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(opzioni) { _, index ->
                when (index) {
                    0 -> condividiFile(file)
                    1 -> {
                        condividiFile(file)
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (file.exists()) file.delete()
                        }, 30000)
                    }
                    2 -> if (file.delete()) Toast.makeText(this, "Eliminato", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun selezionaCartellaDestinazione() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, PICK_FOLDER_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FOLDER_REQUEST_CODE && resultCode == RESULT_OK) {
            val treeUri = data?.data ?: return
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            esportaTuttiIFile(treeUri)
        }
    }

    private fun esportaTuttiIFile(treeUri: Uri) {
        val directory = getExternalFilesDir(null)
        val files = directory?.listFiles { file -> file.extension == "wav" } ?: return
        val pickedDir = DocumentFile.fromTreeUri(this, treeUri)

        var contatore = 0
        files.forEach { file ->
            try {
                val newFile = pickedDir?.createFile("audio/wav", file.name)
                newFile?.uri?.let { uri ->
                    contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                    contatore++
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        if (contatore > 0) {
            // Dopo l'esportazione, chiediamo se vogliamo cancellare i file originali
            AlertDialog.Builder(this)
                .setTitle("Esportazione completata")
                .setMessage("Vuoi eliminare i file dallo storico interno per liberare spazio?")
                .setPositiveButton("Sì, svuota storico") { _, _ ->
                    files.forEach { it.delete() }
                    Toast.makeText(this, "Storico svuotato", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("No, mantieni", null)
                .show()
        }
    }


    private fun condividiFile(file: File) {
        try {
            val contentUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, contentUri)
                intent.setType("audio/wav")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_title)))
        } catch (e: Exception) {
            Toast.makeText(this, "Errore: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
            return false
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}