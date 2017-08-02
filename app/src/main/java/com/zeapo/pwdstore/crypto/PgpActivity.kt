package com.zeapo.pwdstore.crypto

import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.graphics.Typeface
import android.os.AsyncTask
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.preference.PreferenceManager
import android.support.v7.app.AppCompatActivity
import android.text.TextUtils
import android.text.method.PasswordTransformationMethod
import android.util.Log
import android.view.*
import android.widget.*
import com.zeapo.pwdstore.PasswordEntry
import com.zeapo.pwdstore.R
import com.zeapo.pwdstore.UserPreference
import com.zeapo.pwdstore.pwgenDialogFragment
import kotlinx.android.synthetic.main.decrypt_layout.*
import kotlinx.android.synthetic.main.encrypt_layout.*
import org.apache.commons.io.FileUtils
import org.openintents.openpgp.IOpenPgpService2
import org.openintents.openpgp.OpenPgpError
import org.openintents.openpgp.util.OpenPgpApi
import org.openintents.openpgp.util.OpenPgpApi.*
import org.openintents.openpgp.util.OpenPgpServiceConnection
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset

class PgpActivity : AppCompatActivity(), OpenPgpServiceConnection.OnBound {
    val clipboard: ClipboardManager by lazy {
        getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    var passwordEntry: PasswordEntry? = null

    val name: String by lazy { intent.getStringExtra("NAME") }
    val repoPath: String by lazy { intent.getStringExtra("REPO_PATH") }
    val path: String by lazy { intent.getStringExtra("FILE_PATH") }
    val parentPath: String by lazy {
        // when encrypting we pass "PARENT_PATH" as we do not have a file
        if (operation == "ENCRYPT") intent.getStringExtra("PARENT_PATH")
        else File(path).parentFile.absolutePath
    }
    val cat: String by lazy { parentPath.replace(repoPath, "") }
    val operation: String by lazy { intent.getStringExtra("OPERATION") }

    val settings: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(this)
    }
    val keyIDs: MutableSet<String> by lazy {
        settings.getStringSet("openpgp_key_ids_set", emptySet())
    }
    var mServiceConnection: OpenPgpServiceConnection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        // some persistence
        val providerPackageName = settings.getString("openpgp_provider_list", "")

        if (TextUtils.isEmpty(providerPackageName)) {
            Toast.makeText(this, this.resources.getString(R.string.provider_toast_text), Toast.LENGTH_LONG).show()
            val intent = Intent(this, UserPreference::class.java)
            startActivityForResult(intent, OPEN_PGP_BOUND)
        } else {
            // bind to service
            mServiceConnection = OpenPgpServiceConnection(this, providerPackageName, this)
            mServiceConnection?.bindToService()
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
        }

        when (operation) {
            "DECRYPT", "EDIT" -> {
                setContentView(R.layout.decrypt_layout)
                crypto_password_category_decrypt.text = "$cat/"
                crypto_password_file.text = name
            }
            "ENCRYPT" -> {
                setContentView(R.layout.encrypt_layout)
                title = getString(R.string.new_password_title)
                crypto_password_category.text = "$cat/"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mServiceConnection?.unbindFromService()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        // Do not use the value `operation` in this case as it is not valid when editing
        val menuId = when (intent.getStringExtra("OPERATION")) {
            "ENCRYPT" -> R.menu.pgp_handler_new_password
            "DECRYPT" -> R.menu.pgp_handler
            else -> R.menu.pgp_handler
        }

        menuInflater.inflate(menuId, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        when (item?.itemId) {
            android.R.id.home -> {
                setResult(RESULT_CANCELED)
                finish()
            }
            R.id.copy_password -> copyPasswordToClipBoard()
            R.id.share_password_as_plaintext -> shareAsPlaintext()
            R.id.edit_password -> editPassword()
            R.id.crypto_confirm_add -> encrypt()
            R.id.crypto_cancel_add -> setResult(RESULT_CANCELED)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    fun handleClick(view: View) {
        when (view.id) {
            R.id.generate_password -> {
                val df = pwgenDialogFragment()
                df.show(fragmentManager, "generator")
                Log.wtf(TAG, "This should not happen.... PgpHandler.java#handleClick(View) default reached.")
            }
            else -> Log.wtf(TAG, "This should not happen.... PgpHandler.java#handleClick(View) default reached.")
        }// should not happen
    }


    fun showToast(message: String) {
        runOnUiThread({ Toast.makeText(this, message, Toast.LENGTH_SHORT).show() })
    }

    fun handleError(error: OpenPgpError) {
        showToast("Error from OpenKeyChain : " + error.message)
        Log.e(TAG, "onError getErrorId:" + error.errorId)
        Log.e(TAG, "onError getMessage:" + error.message)
    }

    private fun decryptAndVerify(): Unit {
        val data = Intent()
        data.action = ACTION_DECRYPT_VERIFY

        val iStream = FileUtils.openInputStream(File(path))
        val oStream = ByteArrayOutputStream()

        val api = OpenPgpApi(this, mServiceConnection?.service)

        api.executeApiAsync(data, iStream, oStream, { result: Intent? ->
            when (result?.getIntExtra(RESULT_CODE, RESULT_CODE_ERROR)) {
                RESULT_CODE_SUCCESS -> {
                    try {
                        val showPassword = settings.getBoolean("show_password", true)
                        val showExtraContent = settings.getBoolean("show_extra_content", true)

                        crypto_container_decrypt.visibility = View.VISIBLE

                        val monoTypeface = Typeface.createFromAsset(assets, "fonts/sourcecodepro.ttf")
                        val entry = PasswordEntry(oStream)

                        passwordEntry = entry

                        if (operation == "EDIT") {
                            editPassword()
                            return@executeApiAsync
                        }

                        crypto_password_show.typeface = monoTypeface
                        crypto_password_show.text = entry.password

                        crypto_password_toggle_show.visibility = if (showPassword) View.GONE else View.VISIBLE
                        crypto_password_show.transformationMethod = if (showPassword) {
                            null
                        } else {
                            HoldToShowPasswordTransformation(
                                    crypto_password_toggle_show,
                                    Runnable { crypto_password_show.text = entry.password }
                            )
                        }

                        if (entry.hasExtraContent()) {
                            crypto_extra_show_layout.visibility = if (showExtraContent) View.VISIBLE else View.GONE

                            crypto_extra_show.typeface = monoTypeface
                            crypto_extra_show.text = entry.extraContent

                            if (entry.hasUsername()) {
                                crypto_username_show.visibility = View.VISIBLE
                                crypto_username_show_label.visibility = View.VISIBLE
                                crypto_copy_username.visibility = View.VISIBLE

                                crypto_copy_username.setOnClickListener { copyUsernameToClipBoard(entry.username) }
                                crypto_username_show.typeface = monoTypeface
                                crypto_username_show.text = entry.username
                            } else {
                                crypto_username_show.visibility = View.GONE
                                crypto_username_show_label.visibility = View.GONE
                                crypto_copy_username.visibility = View.GONE
                            }
                        }

                        if (settings.getBoolean("copy_on_decrypt", true)) {
                            copyPasswordToClipBoard()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "An Exception occurred", e)
                    }
                }
                RESULT_CODE_USER_INTERACTION_REQUIRED -> {
                    Log.i(TAG, "RESULT_CODE_USER_INTERACTION_REQUIRED")

                    val pi: PendingIntent = result.getParcelableExtra(RESULT_INTENT)
                    try {
                        this@PgpActivity.startIntentSenderFromChild(
                                this@PgpActivity, pi.intentSender, REQUEST_DECRYPT,
                                null, 0, 0, 0)
                    } catch (e: IntentSender.SendIntentException) {
                        Log.e(TAG, "SendIntentException", e)
                    }
                }
                RESULT_CODE_ERROR -> {
                    // TODO show what kind of error it is
                    /* For example:
                     * No suitable key found -> no key in OpenKeyChain
                     *
                     * Check in open-pgp-lib how their definitions and error code
                     */
                    val error: OpenPgpError = result.getParcelableExtra(RESULT_ERROR)
                    handleError(error)
                }
            }

        })
    }

    /**
     * Encrypts the password and the extra content
     */
    private fun encrypt(): Unit {
        val name = crypto_password_file_edit.text.toString()
        val pass = crypto_password_edit.text.toString()
        val extra = crypto_extra_edit.text.toString()

        if (name.isEmpty()) {
            showToast(resources.getString(R.string.file_toast_text))
            return
        }

        if (pass.isEmpty() && extra.isEmpty()) {
            showToast(resources.getString(R.string.empty_toast_text))
            return
        }

        val data = Intent()
        data.action = OpenPgpApi.ACTION_ENCRYPT

        // EXTRA_KEY_IDS requires long[]
        val longKeys = keyIDs.map { it.toLong() }
        data.putExtra(OpenPgpApi.EXTRA_KEY_IDS, longKeys.toLongArray())
        data.putExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, true)

        // TODO Check if we could use PasswordEntry to generate the file
        val iStream = ByteArrayInputStream("$pass\n$extra".toByteArray(Charset.forName("UTF-8")))
        val oStream = ByteArrayOutputStream()

        val api = OpenPgpApi(this, mServiceConnection?.service)
        val path = "$parentPath/$name.gpg"

        api.executeApiAsync(data, iStream, oStream, { result: Intent? ->
            when (result?.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)) {
                OpenPgpApi.RESULT_CODE_SUCCESS -> {
                    try {
                        // TODO This might fail, we should check that the write is successful
                        val outputStream = FileUtils.openOutputStream(File(path))
                        outputStream.write(oStream.toByteArray())
                        outputStream.close()

                        val returnIntent = Intent()
                        returnIntent.putExtra("CREATED_FILE", path)
                        returnIntent.putExtra("NAME", name)

                        // if coming from decrypt screen->edit button
                        if (intent.getBooleanExtra("fromDecrypt", false)) {
                            data.putExtra("needCommit", true)
                        }

                        setResult(RESULT_OK, returnIntent)
                        finish()
                    } catch (e: Exception) {
                        Log.e(TAG, "An Exception occurred", e)
                    }
                }
                OpenPgpApi.RESULT_CODE_ERROR -> {
                    // TODO show what kind of error it is
                    /* For example:
                     * No suitable key found -> no key in OpenKeyChain
                     *
                     * Check in open-pgp-lib how their definitions and error code
                     */
                    val error: OpenPgpError = result.getParcelableExtra(OpenPgpApi.RESULT_ERROR)
                    handleError(error)
                }
            }

        })
    }


    /**
     * Opens EncryptActivity with the information for this file to be edited
     */
    fun editPassword() {
        setContentView(R.layout.encrypt_layout)

        title = getString(R.string.edit_password_title)

        val monoTypeface = Typeface.createFromAsset(assets, "fonts/sourcecodepro.ttf")
        crypto_password_edit.setText(passwordEntry?.password)
        crypto_password_edit.typeface = monoTypeface
        crypto_extra_edit.setText(passwordEntry?.extraContent)
        crypto_extra_edit.typeface = monoTypeface

        crypto_password_category.text = "$cat/"
        crypto_password_file_edit.setText(name)
        crypto_password_file_edit.isEnabled = false

        delayTask?.skip = true

        val data = Intent(this, PgpActivity::class.java)
        data.putExtra("OPERATION", "ENCRYPT")
        data.putExtra("fromDecrypt", true)
        intent = data
        invalidateOptionsMenu()

    }

    override fun onError(e: Exception?) {}

    /**
     * The action to take when the PGP service is bound
     */
    override fun onBound(service: IOpenPgpService2?) {
        if (operation in arrayOf("EDIT", "DECRYPT")) decryptAndVerify()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "onActivityResult resultCode: " + resultCode)

        if (data == null) {
            setResult(Activity.RESULT_CANCELED, null)
            finish()
            return
        }

        // try again after user interaction
        if (resultCode == Activity.RESULT_OK) {
            when (requestCode) {
                REQUEST_DECRYPT -> decryptAndVerify()
                else -> {
                    setResult(Activity.RESULT_OK)
                    finish()
                }
            }
        } else if (resultCode == Activity.RESULT_CANCELED) {
            setResult(Activity.RESULT_CANCELED, data)
            finish()
        }
    }

    private inner class HoldToShowPasswordTransformation constructor(button: Button, private val onToggle: Runnable) :
            PasswordTransformationMethod(), View.OnTouchListener {
        private var shown = false

        init {
            button.setOnTouchListener(this)
        }

        override fun getTransformation(charSequence: CharSequence, view: View): CharSequence {
            return if (shown) charSequence else super.getTransformation("12345", view)
        }

        override fun onTouch(view: View, motionEvent: MotionEvent): Boolean {
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    shown = true
                    onToggle.run()
                }
                MotionEvent.ACTION_UP -> {
                    shown = false
                    onToggle.run()
                }
            }
            return false
        }
    }

    fun copyPasswordToClipBoard() {

        if (findViewById(R.id.crypto_password_show) == null)
            return

        setTimer()

        val clip = ClipData.newPlainText("pgp_handler_result_pm", passwordEntry?.password)
        clipboard.primaryClip = clip

        var clearAfter = 45
        try {
            clearAfter = Integer.parseInt(settings.getString("general_show_time", "45"))
        } catch (e: NumberFormatException) {
            // ignore and keep default
        }

        showToast(this.resources.getString(R.string.clipboard_password_toast_text, clearAfter))
    }

    fun copyUsernameToClipBoard(username: String) {
        val clip = ClipData.newPlainText("pgp_handler_result_pm", username)
        clipboard.primaryClip = clip
        showToast(resources.getString(R.string.clipboard_username_toast_text))
    }


    fun shareAsPlaintext() {
        if (findViewById(R.id.share_password_as_plaintext) == null)
            return

        val sendIntent = Intent()
        sendIntent.action = Intent.ACTION_SEND
        sendIntent.putExtra(Intent.EXTRA_TEXT, passwordEntry?.password)
        sendIntent.type = "text/plain"
        startActivity(Intent.createChooser(sendIntent, resources.getText(R.string.send_plaintext_password_to)))//Always show a picker to give the user a chance to cancel
    }

    fun setTimer() {
        delayTask?.skip = true

        // launch a new one
        delayTask = DelayShow(this)
        delayTask?.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
    }

    inner class DelayShow(val activity: PgpActivity) : AsyncTask<Void, Int, Boolean>() {
        internal val pb: ProgressBar by lazy { pbLoading }
        internal var skip = false
        internal var showTime: Int = 0

        val settings: SharedPreferences by lazy {
            PreferenceManager.getDefaultSharedPreferences(activity)
        }

        override fun onPreExecute() {
            try {
                showTime = Integer.parseInt(settings.getString("general_show_time", "45"))
            } catch (e: NumberFormatException) {
                showTime = 45
            }

            val container = findViewById(R.id.crypto_container_decrypt) as LinearLayout
            container.visibility = View.VISIBLE

            val extraText = findViewById(R.id.crypto_extra_show) as TextView

            if (extraText.text.isNotEmpty())
                findViewById(R.id.crypto_extra_show_layout).visibility = View.VISIBLE

            if (showTime == 0) {
                // treat 0 as forever, and the user must exit and/or clear clipboard on their own
                cancel(true)
            } else {
                this.pb.max = showTime
            }
        }

        override fun doInBackground(vararg params: Void): Boolean? {
            var current = 0
            while (current < showTime) {
                SystemClock.sleep(1000)
                current++
                publishProgress(current)
            }
            return true
        }

        override fun onPostExecute(b: Boolean?) {
            if (skip) return

            // only clear the clipboard if we automatically copied the password to it
            if (settings.getBoolean("copy_on_decrypt", true)) {
                Log.d("DELAY_SHOW", "Clearing the clipboard")
                val clip = ClipData.newPlainText("pgp_handler_result_pm", "")
                clipboard.primaryClip = clip
                if (settings.getBoolean("clear_clipboard_20x", false)) {
                    val handler = Handler()
                    for (i in 0..18) {
                        val count = i.toString()
                        handler.postDelayed({ clipboard.primaryClip = ClipData.newPlainText(count, count) }, (i * 500).toLong())
                    }
                }
            }

            if (crypto_password_show != null) {
                passwordEntry = null
                // clear password; if decrypt changed to encrypt layout via edit button, no need
                crypto_password_show.text = ""
                crypto_extra_show.text = ""
                crypto_extra_show_layout.visibility = View.INVISIBLE
                crypto_container_decrypt.visibility = View.INVISIBLE
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }

        override fun onProgressUpdate(vararg values: Int?) {
            this.pb.progress = values[0] ?: 0
        }
    }

    companion object {
        val OPEN_PGP_BOUND = 101
        val REQUEST_EDIT = 201
        val REQUEST_DECRYPT = 202

        val TAG = "PgpActivity"

        private var delayTask: DelayShow? = null
    }
}

