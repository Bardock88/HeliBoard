package helium314.keyboard.settings.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.Dictionary
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.LocaleUtils
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.common.LocaleUtils.localizedDisplayName
import helium314.keyboard.latin.common.splitOnWhitespace
import helium314.keyboard.latin.settings.USER_DICTIONARY_SUFFIX
import helium314.keyboard.latin.utils.DICTIONARY_URL
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import helium314.keyboard.latin.utils.SubtypeLocaleUtils
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.getDictionaryLocales
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchScreen
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.DictionaryDialog
import helium314.keyboard.settings.dictionaryFilePicker
import java.io.File
import java.util.Locale

@Composable
fun DictionaryScreen(
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val enabledLanguages = SubtypeSettings.getEnabledSubtypes(ctx.prefs(), true).map { it.locale().language }
    val comparer = compareBy<Locale>({ it.language !in enabledLanguages }, { it.displayName }) // todo: could also prefer if there is a user-added dict
    val dictionaryLocales = getDictionaryLocales(ctx).sortedWith(comparer).toMutableList()
    dictionaryLocales.add(0, Locale(SubtypeLocaleUtils.NO_LANGUAGE))
    var selectedLocale: Locale? by remember { mutableStateOf(null) }
    var showAddDictDialog by remember { mutableStateOf(false) }
    val dictPicker = dictionaryFilePicker(selectedLocale)
    SearchScreen(
        onClickBack = onClickBack,
        title = { Text(stringResource(R.string.dictionary_settings_category)) },
        filteredItems = { term ->
            if (term.isBlank()) dictionaryLocales
            else dictionaryLocales.filter {
                    it.language != SubtypeLocaleUtils.NO_LANGUAGE &&
                    it.localizedDisplayName(ctx).replace("(", "")
                        .splitOnWhitespace().any { it.startsWith(term, true) }
                }
        },
        itemContent = {
            if (it.language == SubtypeLocaleUtils.NO_LANGUAGE) {
                Text(stringResource(R.string.add_new_dictionary_title), Modifier.clickable { showAddDictDialog = true })
            } else {
                Column(
                    Modifier.clickable { selectedLocale = it }
                        .padding(vertical = 6.dp, horizontal = 16.dp)
                        .fillMaxWidth()
                ) {
                    val (dicts, hasInternal) = getUserAndInternalDictionaries(ctx, it)
                    val types = dicts.mapTo(mutableListOf()) { it.name.substringBefore("_${USER_DICTIONARY_SUFFIX}") }
                    if (hasInternal && !types.contains(Dictionary.TYPE_MAIN))
                        types.add(0, stringResource(R.string.internal_dictionary_summary))
                    Text(it.localizedDisplayName(ctx))
                    Text(
                        types.joinToString(", "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
    if (showAddDictDialog) {
        ConfirmationDialog(
            onDismissRequest = { showAddDictDialog = false },
            onConfirmed = {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/octet-stream")
                dictPicker.launch(intent)
            },
            title = { Text(stringResource(R.string.add_new_dictionary_title)) },
            text = {
                // todo: no html in compose
                val dictLink = "<a href='$DICTIONARY_URL'>" + ctx.getString(R.string.dictionary_link_text) + "</a>"
                Text(stringResource(R.string.add_dictionary, dictLink))
            }
        )
    }
    if (selectedLocale != null) {
        DictionaryDialog(
            onDismissRequest = { selectedLocale = null },
            locale = selectedLocale!!
        )
    }
}

/** @return list of user dictionary files and whether an internal dictionary exists */
fun getUserAndInternalDictionaries(context: Context, locale: Locale): Pair<List<File>, Boolean> {
    val userDicts = mutableListOf<File>()
    var hasInternalDict = false
    val userLocaleDir = File(DictionaryInfoUtils.getCacheDirectoryForLocale(locale, context))
    if (userLocaleDir.exists() && userLocaleDir.isDirectory) {
        userLocaleDir.listFiles()?.forEach {
            if (it.name.endsWith(USER_DICTIONARY_SUFFIX))
                userDicts.add(it)
            else if (it.name.startsWith(DictionaryInfoUtils.MAIN_DICT_PREFIX))
                hasInternalDict = true
        }
    }
    if (hasInternalDict)
        return userDicts to true
    val internalDicts = DictionaryInfoUtils.getAssetsDictionaryList(context) ?: return userDicts to false
    val best = LocaleUtils.getBestMatch(locale, internalDicts.toList()) {
        DictionaryInfoUtils.extractLocaleFromAssetsDictionaryFile(it)?.constructLocale() ?: SubtypeLocaleUtils.NO_LANGUAGE.constructLocale()
    }
    return userDicts to (best != null)
}
