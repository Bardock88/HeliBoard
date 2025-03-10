/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.latin.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.utils.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.widget.doAfterTextChanged
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import kotlinx.serialization.json.Json
import helium314.keyboard.dictionarypack.DictionaryPackConstants
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.latin.utils.ChecksumCalculator
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.keyboard_parser.LAYOUT_NUMBER
import helium314.keyboard.keyboard.internal.keyboard_parser.LAYOUT_NUMPAD
import helium314.keyboard.keyboard.internal.keyboard_parser.LAYOUT_NUMPAD_LANDSCAPE
import helium314.keyboard.keyboard.internal.keyboard_parser.LAYOUT_PHONE
import helium314.keyboard.keyboard.internal.keyboard_parser.LAYOUT_PHONE_SYMBOLS
import helium314.keyboard.keyboard.internal.keyboard_parser.LAYOUT_SYMBOLS
import helium314.keyboard.keyboard.internal.keyboard_parser.LAYOUT_SYMBOLS_ARABIC
import helium314.keyboard.keyboard.internal.keyboard_parser.LAYOUT_SYMBOLS_SHIFTED
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import helium314.keyboard.latin.SystemBroadcastReceiver
import helium314.keyboard.latin.checkVersionUpgrade
import helium314.keyboard.latin.common.FileUtils
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.common.splitOnWhitespace
import helium314.keyboard.latin.settings.SeekBarDialogPreference.ValueProxy
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.latin.utils.infoDialog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Suppress("KotlinConstantConditions") // build type might be a constant, but depends on... build type!
class AdvancedSettingsFragment : SubScreenFragment() {
    private val libfile by lazy { File(requireContext().filesDir.absolutePath + File.separator + JniUtils.JNI_LIB_IMPORT_FILE_NAME) }
    private val backupFilePatterns by lazy { listOf(
        "blacklists/.*\\.txt".toRegex(),
//        "layouts/$CUSTOM_LAYOUT_PREFIX+\\..{0,4}".toRegex(), // can't expect a period at the end, as this would break restoring older backups
        "dicts/.*/.*user\\.dict".toRegex(),
        "UserHistoryDictionary.*/UserHistoryDictionary.*\\.(body|header)".toRegex(),
        "custom_background_image.*".toRegex(),
        "custom_font".toRegex(),
    ) }

    // is there any way to get additional information into the ActivityResult? would remove the need for 5 times the (almost) same code
    private val libraryFilePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = it.data?.data ?: return@registerForActivityResult
        copyLibrary(uri)
    }

    private val backupFilePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = it.data?.data ?: return@registerForActivityResult
        backup(uri)
    }

    private val restoreFilePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = it.data?.data ?: return@registerForActivityResult
        restore(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupPreferences()
    }

    private fun setupPreferences() {
        addPreferencesFromResource(R.xml.prefs_screen_advanced)
        setDebugPrefVisibility()
        val context = requireContext()

        // When we are called from the Settings application but we are not already running, some
        // singleton and utility classes may not have been initialized.  We have to call
        // initialization method of these classes here. See {@link LatinIME#onCreate()}.
        AudioAndHapticFeedbackManager.init(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            removePreference(Settings.PREF_SHOW_SETUP_WIZARD_ICON)
        }
        if (BuildConfig.BUILD_TYPE == "nouserlib") {
            removePreference("load_gesture_library")
        }
        setupKeyLongpressTimeoutSettings()
        setupEmojiSdkSetting()
        setupLanguageSwipeDistanceSettings()
        updateLangSwipeDistanceVisibility(sharedPreferences)
        findPreference<Preference>("load_gesture_library")?.setOnPreferenceClickListener { onClickLoadLibrary() }
        findPreference<Preference>("backup_restore")?.setOnPreferenceClickListener { showBackupRestoreDialog() }

        findPreference<Preference>("custom_symbols_number_layouts")?.setOnPreferenceClickListener {
            showCustomizeSymbolNumberLayoutsDialog()
            true
        }
        findPreference<Preference>("custom_functional_key_layouts")?.setOnPreferenceClickListener {
//            showCustomizeFunctionalKeyLayoutsDialog()
            true
        }

        findPreference<Preference>(Settings.PREF_CUSTOM_CURRENCY_KEY)?.setOnPreferenceClickListener {
            customCurrencyDialog()
            true
        }

        findPreference<Preference>("switch_after")?.setOnPreferenceClickListener {
            switchToMainDialog()
            true
        }
    }

    override fun onStart() {
        super.onStart()
        // Remove debug preference. This is already done in onCreate, but if we come back from
        // debug prefs and have just disabled debug settings, they should disappear.
        setDebugPrefVisibility()
    }

    private fun setDebugPrefVisibility() {
        if (!BuildConfig.DEBUG && !sharedPreferences.getBoolean(DebugSettings.PREF_SHOW_DEBUG_SETTINGS, false)) {
            removePreference(Settings.SCREEN_DEBUG)
        }
    }

    private fun showCustomizeSymbolNumberLayoutsDialog() {
/*        val layoutNames = RawKeyboardParser.symbolAndNumberLayouts.map { it.getStringResourceOrName("layout_", requireContext()) }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.customize_symbols_number_layouts)
            .setItems(layoutNames) { di, i ->
                di.dismiss()
                customizeSymbolNumberLayout(RawKeyboardParser.symbolAndNumberLayouts[i])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
*/    }
/*
    private fun customizeSymbolNumberLayout(layoutName: String) {
        val customLayoutName = getCustomLayoutFiles(requireContext()).map { it.name }
            .firstOrNull { it.startsWith("$CUSTOM_LAYOUT_PREFIX$layoutName.") }
        val originalLayout = if (customLayoutName != null) null
            else {
                requireContext().assets.list("layouts")?.firstOrNull { it.startsWith("$layoutName.") }
                    ?.let { requireContext().assets.open("layouts" + File.separator + it).reader().readText() }
            }
        val displayName = layoutName.getStringResourceOrName("layout_", requireContext())
        editCustomLayout(customLayoutName ?: "$CUSTOM_LAYOUT_PREFIX$layoutName.", requireContext(), originalLayout, displayName)
    }

    private fun showCustomizeFunctionalKeyLayoutsDialog() {
        val list = listOf(CUSTOM_FUNCTIONAL_LAYOUT_NORMAL, CUSTOM_FUNCTIONAL_LAYOUT_SYMBOLS, CUSTOM_FUNCTIONAL_LAYOUT_SYMBOLS_SHIFTED)
            .map { it.substringBeforeLast(".") }
        val layoutNames = list.map { it.substringAfter(CUSTOM_LAYOUT_PREFIX).getStringResourceOrName("layout_", requireContext()) }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.customize_functional_key_layouts)
            .setItems(layoutNames) { di, i ->
                di.dismiss()
                customizeFunctionalKeysLayout(list[i])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun customizeFunctionalKeysLayout(layoutName: String) {
        val customLayoutName = getCustomLayoutFiles(requireContext()).map { it.name }
            .firstOrNull { it.startsWith("$layoutName.") }
        val originalLayout = if (customLayoutName != null) null
            else {
                val defaultLayoutName = if (Settings.getInstance().isTablet) "functional_keys_tablet.json" else "functional_keys.json"
                requireContext().assets.open("layouts" + File.separator + defaultLayoutName).reader().readText()
            }
        val displayName = layoutName.substringAfter(CUSTOM_LAYOUT_PREFIX).getStringResourceOrName("layout_", requireContext())
        editCustomLayout(customLayoutName ?: "$layoutName.", requireContext(), originalLayout, displayName)
    }
*/
    @SuppressLint("ApplySharedPref")
    private fun onClickLoadLibrary(): Boolean {
        // get architecture for telling user which file to use
        val abi = Build.SUPPORTED_ABIS[0]
        // show delete / add dialog
        val builder = AlertDialog.Builder(requireContext())
                .setTitle(R.string.load_gesture_library)
                .setMessage(requireContext().getString(R.string.load_gesture_library_message, abi))
                .setPositiveButton(R.string.load_gesture_library_button_load) { _, _ ->
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/octet-stream")
                    libraryFilePicker.launch(intent)
                }
                .setNegativeButton(android.R.string.cancel, null)
        if (libfile.exists()) {
            builder.setNeutralButton(R.string.load_gesture_library_button_delete) { _, _ ->
                libfile.delete()
                PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().remove(Settings.PREF_LIBRARY_CHECKSUM).commit()
                Runtime.getRuntime().exit(0)
            }
        }
        builder.show()
        return true
    }

    private fun copyLibrary(uri: Uri) {
        val tmpfile = File(requireContext().filesDir.absolutePath + File.separator + "tmplib")
        try {
            val otherTemporaryFile = File(requireContext().filesDir.absolutePath + File.separator + "tmpfile")
            FileUtils.copyContentUriToNewFile(uri, requireContext(), otherTemporaryFile)
            val inputStream = FileInputStream(otherTemporaryFile)
            val outputStream = FileOutputStream(tmpfile)
            outputStream.use {
                tmpfile.setReadOnly() // as per recommendations in https://developer.android.com/about/versions/14/behavior-changes-14#safer-dynamic-code-loading
                FileUtils.copyStreamToOtherStream(inputStream, it)
            }
            otherTemporaryFile.delete()

            val checksum = ChecksumCalculator.checksum(tmpfile.inputStream()) ?: ""
            if (checksum == JniUtils.expectedDefaultChecksum()) {
                renameToLibfileAndRestart(tmpfile, checksum)
            } else {
                val abi = Build.SUPPORTED_ABIS[0]
                AlertDialog.Builder(requireContext())
                    .setMessage(getString(R.string.checksum_mismatch_message, abi))
                    .setPositiveButton(android.R.string.ok) { _, _ -> renameToLibfileAndRestart(tmpfile, checksum) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> tmpfile.delete() }
                    .show()
            }
        } catch (e: IOException) {
            tmpfile.delete()
            // should inform user, but probably the issues will only come when reading the library
        }
    }

    @SuppressLint("ApplySharedPref")
    private fun renameToLibfileAndRestart(file: File, checksum: String) {
        libfile.delete()
        // store checksum in default preferences (soo JniUtils)
        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putString(Settings.PREF_LIBRARY_CHECKSUM, checksum).commit()
        file.renameTo(libfile)
        Runtime.getRuntime().exit(0) // exit will restart the app, so library will be loaded
    }

    private fun showBackupRestoreDialog(): Boolean {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.backup_restore_title)
            .setMessage(R.string.backup_restore_message)
            .setNegativeButton(R.string.button_backup) { _, _ ->
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(
                        Intent.EXTRA_TITLE,
                        requireContext().getString(R.string.english_ime_name)
                            .replace(" ", "_") + "_backup.zip"
                    )
                    .setType("application/zip")
                backupFilePicker.launch(intent)
            }
            .setPositiveButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.button_restore) { _, _ ->
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip")
                restoreFilePicker.launch(intent)
            }
            .show()
        return true
    }

    private fun backup(uri: Uri) {
        // zip all files matching the backup patterns
        // essentially this is the typed words information, and user-added dictionaries
        val filesDir = requireContext().filesDir ?: return
        val filesPath = filesDir.path + File.separator
        val files = mutableListOf<File>()
        filesDir.walk().forEach { file ->
            val path = file.path.replace(filesPath, "")
            if (backupFilePatterns.any { path.matches(it) })
                files.add(file)
        }
        val protectedFilesDir = DeviceProtectedUtils.getFilesDir(requireContext())
        val protectedFilesPath = protectedFilesDir.path + File.separator
        val protectedFiles = mutableListOf<File>()
        protectedFilesDir.walk().forEach { file ->
            val path = file.path.replace(protectedFilesPath, "")
            if (backupFilePatterns.any { path.matches(it) })
                protectedFiles.add(file)
        }
        var error: String? = ""
        val wait = CountDownLatch(1)
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
            try {
                activity?.contentResolver?.openOutputStream(uri)?.use { os ->
                    // write files to zip
                    val zipStream = ZipOutputStream(os)
                    files.forEach {
                        val fileStream = FileInputStream(it).buffered()
                        zipStream.putNextEntry(ZipEntry(it.path.replace(filesPath, "")))
                        fileStream.copyTo(zipStream, 1024)
                        fileStream.close()
                        zipStream.closeEntry()
                    }
                    protectedFiles.forEach {
                        val fileStream = FileInputStream(it).buffered()
                        zipStream.putNextEntry(ZipEntry(it.path.replace(protectedFilesDir.path, "unprotected")))
                        fileStream.copyTo(zipStream, 1024)
                        fileStream.close()
                        zipStream.closeEntry()
                    }
                    zipStream.putNextEntry(ZipEntry(PREFS_FILE_NAME))
                    settingsToJsonStream(sharedPreferences.all, zipStream)
                    zipStream.closeEntry()
                    zipStream.putNextEntry(ZipEntry(PROTECTED_PREFS_FILE_NAME))
                    settingsToJsonStream(PreferenceManager.getDefaultSharedPreferences(requireContext()).all, zipStream)
                    zipStream.closeEntry()
                    zipStream.close()
                }
            } catch (t: Throwable) {
                error = t.message
                Log.w(TAG, "error during backup", t)
            } finally {
                wait.countDown()
            }
        }
        wait.await()
        if (!error.isNullOrBlank()) {
            // inform about every error
            infoDialog(requireContext(), requireContext().getString(R.string.backup_error, error))
        }
    }

    private fun restore(uri: Uri) {
        var error: String? = ""
        val wait = CountDownLatch(1)
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
            try {
                activity?.contentResolver?.openInputStream(uri)?.use { inputStream ->
                    ZipInputStream(inputStream).use { zip ->
                        var entry: ZipEntry? = zip.nextEntry
                        val filesDir = requireContext().filesDir?.path ?: return@execute
                        val deviceProtectedFilesDir = DeviceProtectedUtils.getFilesDir(requireContext()).path
                        Settings.getInstance().stopListener()
                        while (entry != null) {
                            if (entry.name.startsWith("unprotected${File.separator}")) {
                                val adjustedName = entry.name.substringAfter("unprotected${File.separator}")
                                if (backupFilePatterns.any { adjustedName.matches(it) }) {
                                    val targetFileName = upgradeFileNames(adjustedName)
                                    val file = File(deviceProtectedFilesDir, targetFileName)
                                    FileUtils.copyStreamToNewFile(zip, file)
                                }
                            } else if (backupFilePatterns.any { entry!!.name.matches(it) }) {
                                val targetFileName = upgradeFileNames(entry.name)
                                val file = File(filesDir, targetFileName)
                                FileUtils.copyStreamToNewFile(zip, file)
                            } else if (entry.name == PREFS_FILE_NAME) {
                                val prefLines = String(zip.readBytes()).split("\n")
                                sharedPreferences.edit().clear().apply()
                                readJsonLinesToSettings(prefLines, sharedPreferences)
                            } else if (entry.name == PROTECTED_PREFS_FILE_NAME) {
                                val prefLines = String(zip.readBytes()).split("\n")
                                val protectedPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                                protectedPrefs.edit().clear().apply()
                                readJsonLinesToSettings(prefLines, protectedPrefs)
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }
            } catch (t: Throwable) {
                error = t.message
                Log.w(TAG, "error during restore", t)
            } finally {
                wait.countDown()
            }
        }
        wait.await()
        if (!error.isNullOrBlank()) {
            // inform about every error
            infoDialog(requireContext(), requireContext().getString(R.string.restore_error, error))
        }
        checkVersionUpgrade(requireContext())
        Settings.getInstance().startListener()
        SubtypeSettings.reloadEnabledSubtypes(requireContext())
        val newDictBroadcast = Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION)
        activity?.sendBroadcast(newDictBroadcast)
        // reload current prefs screen
        preferenceScreen.removeAll()
        setupPreferences()
//        onCustomLayoutFileListChanged()
        KeyboardSwitcher.getInstance().forceUpdateKeyboardTheme(requireContext())
    }

    // todo (later): remove this when new package name has been in use for long enough, this is only for migrating from old openboard name
    private fun upgradeFileNames(originalName: String): String {
        return when {
            originalName.endsWith(USER_DICTIONARY_SUFFIX) -> {
                // replace directory after switch to language tag
                val dirName = originalName.substringAfter(File.separator).substringBefore(File.separator)
                originalName.replace(dirName, dirName.constructLocale().toLanguageTag())
            }
            originalName.startsWith("blacklists") -> {
                // replace file name after switch to language tag
                val fileName = originalName.substringAfter("blacklists${File.separator}").substringBefore(".txt")
                originalName.replace(fileName, fileName.constructLocale().toLanguageTag())
            }
            originalName.startsWith("layouts") -> {
                // replace file name after switch to language tag, but only if it's not a layout
                val localeString = originalName.substringAfter(".").substringBefore(".")
                if (localeString in listOf(LAYOUT_SYMBOLS, LAYOUT_SYMBOLS_SHIFTED, LAYOUT_SYMBOLS_ARABIC, LAYOUT_NUMBER, LAYOUT_NUMPAD, LAYOUT_NUMPAD_LANDSCAPE, LAYOUT_PHONE, LAYOUT_PHONE_SYMBOLS))
                    return originalName // it's a layout!
                val locale = localeString.constructLocale()
                if (locale.toLanguageTag() != "und")
                    originalName.replace(localeString, locale.toLanguageTag())
                else
                    originalName // no valid locale -> must be symbols layout, don't change
            }
            originalName.startsWith("UserHistoryDictionary") -> {
                val localeString = originalName.substringAfter(".").substringBefore(".")
                val locale = localeString.constructLocale()
                originalName.replace(localeString, locale.toLanguageTag())
            }
            else -> originalName
        }
    }

    private fun customCurrencyDialog() {
        val layout = LinearLayout(requireContext())
        layout.orientation = LinearLayout.VERTICAL
        layout.addView(TextView(requireContext()).apply { setText(R.string.customize_currencies_detail) })
        val et = EditText(requireContext()).apply { setText(sharedPreferences.getString(Settings.PREF_CUSTOM_CURRENCY_KEY, "")) }
        layout.addView(et)
        val padding = ResourceUtils.toPx(8, resources)
        layout.setPadding(3 * padding, padding, padding, padding)
        val d = AlertDialog.Builder(requireContext())
            .setTitle(R.string.customize_currencies)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                sharedPreferences.edit { putString(Settings.PREF_CUSTOM_CURRENCY_KEY, et.text.toString()) }
                KeyboardLayoutSet.onSystemLocaleChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.button_default) { _, _ -> sharedPreferences.edit { putString(Settings.PREF_CUSTOM_CURRENCY_KEY, "") } }
            .create()
        et.doAfterTextChanged { d.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = et.text.toString().splitOnWhitespace().none { it.length > 8 } }
        d.show()
    }

    private fun switchToMainDialog() {
        val checked = booleanArrayOf(
            sharedPreferences.getBoolean(Settings.PREF_ABC_AFTER_SYMBOL_SPACE, true),
            sharedPreferences.getBoolean(Settings.PREF_ABC_AFTER_EMOJI, false),
            sharedPreferences.getBoolean(Settings.PREF_ABC_AFTER_CLIP, false),
        )
        val titles = arrayOf(
            requireContext().getString(R.string.after_symbol_and_space),
            requireContext().getString(R.string.after_emoji),
            requireContext().getString(R.string.after_clip),
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.switch_keyboard_after)
            .setMultiChoiceItems(titles, checked) { _, i, b -> checked[i] = b }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                sharedPreferences.edit {
                    putBoolean(Settings.PREF_ABC_AFTER_SYMBOL_SPACE, checked[0])
                    putBoolean(Settings.PREF_ABC_AFTER_EMOJI, checked[1])
                    putBoolean(Settings.PREF_ABC_AFTER_CLIP, checked[2])
                }
            }
            .show()
    }

    private fun setupKeyLongpressTimeoutSettings() {
        val prefs = sharedPreferences
        findPreference<SeekBarDialogPreference>(Settings.PREF_KEY_LONGPRESS_TIMEOUT)?.setInterface(object : ValueProxy {
            override fun writeValue(value: Int, key: String) = prefs.edit().putInt(key, value).apply()

            override fun writeDefaultValue(key: String) = prefs.edit().remove(key).apply()

            override fun readValue(key: String) = prefs.getInt(Settings.PREF_KEY_LONGPRESS_TIMEOUT, Defaults.PREF_KEY_LONGPRESS_TIMEOUT)

            override fun readDefaultValue(key: String) = 300

            override fun getValueText(value: Int) =
                resources.getString(R.string.abbreviation_unit_milliseconds, value.toString())

            override fun feedbackValue(value: Int) {}
        })
    }

    private fun setupEmojiSdkSetting() {
        val prefs = sharedPreferences
        findPreference<SeekBarDialogPreference>(Settings.PREF_EMOJI_MAX_SDK)?.setInterface(object : ValueProxy {
            override fun writeValue(value: Int, key: String) = prefs.edit().putInt(key, value).apply()

            override fun writeDefaultValue(key: String) = prefs.edit().remove(key).apply()

            override fun readValue(key: String) = prefs.getInt(Settings.PREF_EMOJI_MAX_SDK, Build.VERSION.SDK_INT)

            override fun readDefaultValue(key: String) = Build.VERSION.SDK_INT

            override fun getValueText(value: Int) = "Android " + when(value) {
                21 -> "5.0"
                22 -> "5.1"
                23 -> "6"
                24 -> "7.0"
                25 -> "7.1"
                26 -> "8.0"
                27 -> "8.1"
                28 -> "9"
                29 -> "10"
                30 -> "11"
                31 -> "12"
                32 -> "12L"
                33 -> "13"
                34 -> "14"
                35 -> "15"
                else -> "version unknown"
            }

            override fun feedbackValue(value: Int) {}
        })
    }

    private fun setupLanguageSwipeDistanceSettings() {
        val prefs = sharedPreferences
        findPreference<SeekBarDialogPreference>(Settings.PREF_LANGUAGE_SWIPE_DISTANCE)?.setInterface(object : ValueProxy {
            override fun writeValue(value: Int, key: String) = prefs.edit().putInt(key, value).apply()

            override fun writeDefaultValue(key: String) = prefs.edit().remove(key).apply()

            override fun readValue(key: String) = prefs.getInt(Settings.PREF_LANGUAGE_SWIPE_DISTANCE, 5)

            override fun readDefaultValue(key: String) = 5

            override fun getValueText(value: Int) = value.toString()

            override fun feedbackValue(value: Int) {}
        })
    }

    private fun updateLangSwipeDistanceVisibility(prefs: SharedPreferences) {
        val horizontalSpaceSwipe = Settings.readHorizontalSpaceSwipe(prefs)
        val verticalSpaceSwipe = Settings.readVerticalSpaceSwipe(prefs)
        val visibility = horizontalSpaceSwipe == KeyboardActionListener.SWIPE_SWITCH_LANGUAGE
                || verticalSpaceSwipe == KeyboardActionListener.SWIPE_SWITCH_LANGUAGE
        setPreferenceVisible(Settings.PREF_LANGUAGE_SWIPE_DISTANCE, visibility)
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        when (key) {
            Settings.PREF_SHOW_SETUP_WIZARD_ICON -> SystemBroadcastReceiver.toggleAppIcon(requireContext())
            "more_popup_keys" -> KeyboardLayoutSet.onSystemLocaleChanged()
            Settings.PREF_SPACE_HORIZONTAL_SWIPE -> updateLangSwipeDistanceVisibility(prefs)
            Settings.PREF_SPACE_VERTICAL_SWIPE -> updateLangSwipeDistanceVisibility(prefs)
            Settings.PREF_EMOJI_MAX_SDK -> KeyboardSwitcher.getInstance().forceUpdateKeyboardTheme(requireContext())
        }
    }

    companion object {
        @Suppress("UNCHECKED_CAST") // it is checked... but whatever (except string set, because can't check for that))
        private fun settingsToJsonStream(settings: Map<String?, Any?>, out: OutputStream) {
            val booleans = settings.filter { it.key is String && it.value is Boolean } as Map<String, Boolean>
            val ints = settings.filter { it.key is String && it.value is Int } as Map<String, Int>
            val longs = settings.filter { it.key is String && it.value is Long } as Map<String, Long>
            val floats = settings.filter { it.key is String && it.value is Float } as Map<String, Float>
            val strings = settings.filter { it.key is String && it.value is String } as Map<String, String>
            val stringSets = settings.filter { it.key is String && it.value is Set<*> } as Map<String, Set<String>>
            // now write
            out.write("boolean settings\n".toByteArray())
            out.write(Json.encodeToString(booleans).toByteArray())
            out.write("\nint settings\n".toByteArray())
            out.write(Json.encodeToString(ints).toByteArray())
            out.write("\nlong settings\n".toByteArray())
            out.write(Json.encodeToString(longs).toByteArray())
            out.write("\nfloat settings\n".toByteArray())
            out.write(Json.encodeToString(floats).toByteArray())
            out.write("\nstring settings\n".toByteArray())
            out.write(Json.encodeToString(strings).toByteArray())
            out.write("\nstring set settings\n".toByteArray())
            out.write(Json.encodeToString(stringSets).toByteArray())
        }

        private fun readJsonLinesToSettings(list: List<String>, prefs: SharedPreferences): Boolean {
            val i = list.iterator()
            val e = prefs.edit()
            try {
                while (i.hasNext()) {
                    when (i.next()) {
                        "boolean settings" -> Json.decodeFromString<Map<String, Boolean>>(i.next()).forEach { e.putBoolean(it.key, it.value) }
                        "int settings" -> Json.decodeFromString<Map<String, Int>>(i.next()).forEach { e.putInt(it.key, it.value) }
                        "long settings" -> Json.decodeFromString<Map<String, Long>>(i.next()).forEach { e.putLong(it.key, it.value) }
                        "float settings" -> Json.decodeFromString<Map<String, Float>>(i.next()).forEach { e.putFloat(it.key, it.value) }
                        "string settings" -> Json.decodeFromString<Map<String, String>>(i.next()).forEach { e.putString(it.key, it.value) }
                        "string set settings" -> Json.decodeFromString<Map<String, Set<String>>>(i.next()).forEach { e.putStringSet(it.key, it.value) }
                    }
                }
                e.apply()
                return true
            } catch (e: Exception) {
                return false
            }
        }
    }
}

private const val PREFS_FILE_NAME = "preferences.json"
private const val PROTECTED_PREFS_FILE_NAME = "protected_preferences.json"
private const val TAG = "AdvancedSettingsFragment"
