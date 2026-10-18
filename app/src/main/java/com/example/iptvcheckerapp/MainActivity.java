package com.example.iptvcheckerapp;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static final int FILE_PICKER_REQUEST = 1001;

    private Button btnSelectFile, btnStartCheck;
    private TextView tvSelectedFile, tvProgress;
    private EditText etManualLinks;
    private LinearLayout panelSelectionLayout;
    private CheckBox cbSelectAllPanels;
    private Button btnChannelList;

    private List<String> linksList = new ArrayList<>();
    private List<String> allPanelsList = new ArrayList<>();
    private Set<String> selectedPanels = new HashSet<>();
    private String selectedFileName = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initViews();
        setupClickListeners();
        setupManualTextWatcher();
    }

    private void initViews() {
        btnChannelList = findViewById(R.id.btnChannelList);
        btnSelectFile = findViewById(R.id.btnSelectFile);
        btnStartCheck = findViewById(R.id.btnStartCheck);
        tvSelectedFile = findViewById(R.id.tvSelectedFile);
        tvProgress = findViewById(R.id.tvProgress);
        etManualLinks = findViewById(R.id.etManualLinks);
        panelSelectionLayout = findViewById(R.id.panelSelectionLayout);
        cbSelectAllPanels = findViewById(R.id.cbSelectAllPanels);
        btnStartCheck.setEnabled(false);
        btnStartCheck.setAlpha(0.5f);
        cbSelectAllPanels.setChecked(true);
        selectedPanels.clear();
    }

    private void setupManualTextWatcher() {
        etManualLinks.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { updateManualLinksAndPanels(); }
        });
    }

    private void updateManualLinksAndPanels() {
        String manualText = etManualLinks.getText().toString().trim();
        if (manualText.isEmpty()) {
            if (linksList.isEmpty()) {
                allPanelsList.clear();
                selectedPanels.clear();
                updateUI();
            } else {
                updateUI();
            }
            return;
        }
        List<String> manualLinks = extractLinksFromManualText();
        Set<String> tempPanels = new HashSet<>(allPanelsList);
        for (String link : manualLinks) {
            String panelDomain = extractPanelDomain(link);
            if (panelDomain != null) tempPanels.add(panelDomain);
        }
        allPanelsList.clear();
        allPanelsList.addAll(tempPanels);
        updateUI();
    }

    private void setupClickListeners() {
        btnChannelList.setOnClickListener(v -> {
            if (selectedPanels.isEmpty()) {
                Toast.makeText(this, "Lütfen önce panel seçin!", Toast.LENGTH_SHORT).show();
                return;
            }
            List<String> filteredLinks = getFilteredLinks();
            if (filteredLinks.isEmpty()) {
                Toast.makeText(this, "Seçili panellere ait link bulunamadı!", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(this, ChannelListActivity.class);
            intent.putStringArrayListExtra("links", new ArrayList<>(filteredLinks));
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            Toast.makeText(this, "📺 Kanal listesi aranıyor...", Toast.LENGTH_SHORT).show();
        });

        btnSelectFile.setOnClickListener(v -> openFilePicker());
        btnStartCheck.setOnClickListener(v -> startIPTVCheck());

        cbSelectAllPanels.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                selectedPanels.clear();
                selectedPanels.addAll(allPanelsList);
            } else {
                selectedPanels.clear();
            }
            updatePanelCheckboxes();
            updateStartButtonState();
        });
    }

    private void openFilePicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            String[] mimeTypes = {"text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"};
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, FILE_PICKER_REQUEST);
        } catch (Exception e) {
            Toast.makeText(this, "Dosya seçici açılamadı: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_PICKER_REQUEST && resultCode == RESULT_OK && data != null) {
            processSelectedFile(data.getData());
        }
    }

    private void processSelectedFile(Uri fileUri) {
        try {
            selectedFileName = getFileName(fileUri);
            linksList.clear();
            List<String> manualLinks = extractLinksFromManualText();
            Set<String> manualPanels = new HashSet<>();
            for (String link : manualLinks) {
                String panel = extractPanelDomain(link);
                if (panel != null) manualPanels.add(panel);
            }
            allPanelsList.clear();
            selectedPanels.clear();
            allPanelsList.addAll(manualPanels);
            if (selectedFileName.toLowerCase().endsWith(".docx")) readWordFile(fileUri);
            else readTextFile(fileUri);
        } catch (Exception e) {
            Toast.makeText(this, "Dosya okunamadı: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String getFileName(Uri uri) {
        String fileName = null;
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex != -1) fileName = cursor.getString(nameIndex);
            }
        } finally { if (cursor != null) cursor.close(); }
        return fileName != null ? fileName : uri.getLastPathSegment();
    }

    private void readTextFile(Uri fileUri) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(fileUri);
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            StringBuilder fileContent = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) fileContent.append(line).append("\n");
            reader.close();
            findLinksInText(fileContent.toString());
            updateUI();
        } catch (Exception e) { throw new RuntimeException("Text dosyası okunamadı: " + e.getMessage()); }
    }

    private void readWordFile(Uri fileUri) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(fileUri);
            String fileContent = WordReader.readDocxFile(inputStream);
            inputStream.close();
            findLinksInText(fileContent);
            updateUI();
        } catch (Exception e) { throw new RuntimeException("Word dosyası okunamadı: " + e.getMessage()); }
    }

    private void findLinksInText(String text) {
        // "M3U Link:" ile başlayan veya direkt link formatını bulan regex
        String patternString = "(?:M3U\\s*Link\\s*:|https?://)[^\\s\"'<>]*username=[^\\s\"'&]*&password=[^\\s\"'&]*[^\\s\"'<>]*";
        Pattern pattern = Pattern.compile(patternString, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String match = matcher.group().trim();
            // Eğer "M3U Link:" ile başlıyorsa, link kısmını ayır
            String link = match;
            if (match.toLowerCase().contains("m3u link")) {
                // "M3U Link:" kısmını temizle, sadece URL'yi al
                link = match.replaceFirst("(?i)M3U\\s*Link\\s*:", "").trim();
            }
            link = cleanLink(link);
            if (isValidIPTVLink(link) && !linksList.contains(link)) {
                linksList.add(link);
                String panelDomain = extractPanelDomain(link);
                if (panelDomain != null && !allPanelsList.contains(panelDomain)) allPanelsList.add(panelDomain);
            }
        }
        if (linksList.isEmpty()) findLinksAlternative(text);
    }

    private void findLinksAlternative(String text) {
        String[] alternativePatterns = {
                "(?:M3U\\s*Link\\s*:|https?://)[^\\s\"'<>]*?username=[^\\s\"'&]*?&password=[^\\s\"'&]*?[^\\s\"'<>]*",
                "http[^\\s\"'<>]*username[^\\s\"'&]*password[^\\s\"'&]*",
                "get\\.php[?]username=[^\\s\"'&]+&password=[^\\s\"'&]+"
        };
        for (String altPattern : alternativePatterns) {
            Pattern pattern = Pattern.compile(altPattern, Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                String match = matcher.group().trim();
                String link = match;
                if (match.toLowerCase().contains("m3u link")) {
                    link = match.replaceFirst("(?i)M3U\\s*Link\\s*:", "").trim();
                }
                link = cleanLink(link);
                if (!link.startsWith("http") && link.contains("get.php")) link = "http://" + link;
                if (isValidIPTVLink(link) && !linksList.contains(link)) {
                    linksList.add(link);
                    String panelDomain = extractPanelDomain(link);
                    if (panelDomain != null && !allPanelsList.contains(panelDomain)) allPanelsList.add(panelDomain);
                }
            }
            if (!linksList.isEmpty()) break;
        }
    }

    private String extractPanelDomain(String link) {
        try {
            String url = link;
            if (!url.startsWith("http")) url = "http://" + url;
            String host = url.replaceFirst("http://", "").replaceFirst("https://", "");
            if (host.contains("/")) host = host.substring(0, host.indexOf("/"));
            if (host.contains(":")) host = host.substring(0, host.indexOf(":"));
            return host;
        } catch (Exception e) { return "unknown"; }
    }

    private String cleanLink(String link) {
        link = link.replaceAll("[\\n\\r\\t]+", "");
        link = link.replaceAll("&amp;", "&");
        link = link.replaceAll("[\\\"']+$", "");
        link = link.replaceAll("^[\\\"']+", "");
        link = link.replaceAll("\\\\/", "/");
        return link.trim();
    }

    private boolean isValidIPTVLink(String link) {
        if (link == null || link.length() < 10) return false;
        boolean hasHttp = link.startsWith("http://") || link.startsWith("https://");
        boolean hasUsername = link.contains("username=");
        boolean hasPassword = link.contains("password=");
        boolean hasGetPhp = link.contains("get.php");
        return hasHttp && hasUsername && hasPassword && hasGetPhp;
    }

    private void updateUI() {
        String fileInfo = "Seçilen dosya: " + selectedFileName + " (" + linksList.size() + " link)";
        if (selectedFileName.isEmpty() || linksList.isEmpty()) fileInfo = "Dosyadan link yok";
        tvSelectedFile.setText(fileInfo);
        int manualLinkCount = extractLinksFromManualText().size();
        int totalLinks = linksList.size() + manualLinkCount;
        if (totalLinks > 0) {
            updatePanelSelectionUI();
            String progressText = "✅ Toplam " + totalLinks + " link bulundu";
            if (linksList.size() > 0 && manualLinkCount > 0) progressText += " (Dosya: " + linksList.size() + " + Manuel: " + manualLinkCount + ")";
            else if (manualLinkCount > 0) progressText += " (Manuel giriş)";
            progressText += ". Panelleri seçin ve kontrolü başlatın.";
            tvProgress.setText(progressText);
            updateStartButtonState();
        } else {
            tvProgress.setText("❌ Hiç link bulunamadı! Link formatı: http://.../get.php?username=XXX&password=XXX");
            btnStartCheck.setEnabled(false);
            btnStartCheck.setAlpha(0.5f);
            btnChannelList.setEnabled(false);
            btnChannelList.setAlpha(0.5f);
            panelSelectionLayout.setVisibility(View.GONE);
        }
    }

    private void updatePanelSelectionUI() {
        panelSelectionLayout.removeAllViews();
        if (allPanelsList.isEmpty()) {
            TextView noPanelText = new TextView(this);
            noPanelText.setText("Panel bulunamadı");
            noPanelText.setPadding(0, 16, 0, 16);
            panelSelectionLayout.addView(noPanelText);
            return;
        }
        cbSelectAllPanels.setText("Tüm Paneller (" + allPanelsList.size() + ")");
        if (cbSelectAllPanels.isChecked()) {
            selectedPanels.clear();
            selectedPanels.addAll(allPanelsList);
        }
        for (String panel : allPanelsList) {
            CheckBox checkBox = new CheckBox(this);
            checkBox.setText(panel);
            checkBox.setTag(panel);
            checkBox.setChecked(selectedPanels.contains(panel));
            checkBox.setPadding(0, 8, 0, 8);
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                String panelDomain = (String) buttonView.getTag();
                if (cbSelectAllPanels.isChecked()) {
                    cbSelectAllPanels.setChecked(false);
                    selectedPanels.clear();
                    selectedPanels.addAll(allPanelsList);
                    selectedPanels.remove(panelDomain);
                    updatePanelCheckboxes();
                } else {
                    if (isChecked) selectedPanels.add(panelDomain);
                    else selectedPanels.remove(panelDomain);
                }
                updateStartButtonState();
            });
            panelSelectionLayout.addView(checkBox);
        }
        panelSelectionLayout.setVisibility(View.VISIBLE);
    }

    private void updatePanelCheckboxes() {
        for (int i = 0; i < panelSelectionLayout.getChildCount(); i++) {
            View view = panelSelectionLayout.getChildAt(i);
            if (view instanceof CheckBox) {
                CheckBox checkBox = (CheckBox) view;
                String panelDomain = (String) checkBox.getTag();
                checkBox.setOnCheckedChangeListener(null);
                checkBox.setChecked(selectedPanels.contains(panelDomain));
                checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    String domain = (String) buttonView.getTag();
                    if (cbSelectAllPanels.isChecked()) {
                        cbSelectAllPanels.setChecked(false);
                        selectedPanels.clear();
                        selectedPanels.addAll(allPanelsList);
                        selectedPanels.remove(domain);
                        updatePanelCheckboxes();
                    } else {
                        if (isChecked) selectedPanels.add(domain);
                        else selectedPanels.remove(domain);
                    }
                    updateStartButtonState();
                });
                checkBox.setEnabled(true);
            }
        }
    }

    private void updateStartButtonState() {
        boolean hasSelectedPanels = !selectedPanels.isEmpty();
        boolean hasLinks = !linksList.isEmpty() || !extractLinksFromManualText().isEmpty();
        btnChannelList.setEnabled(hasSelectedPanels && hasLinks);
        btnChannelList.setAlpha((hasSelectedPanels && hasLinks) ? 1.0f : 0.5f);
        if (hasSelectedPanels && hasLinks) {
            btnStartCheck.setEnabled(true);
            btnStartCheck.setAlpha(1.0f);
            tvProgress.setText("✅ " + getFilteredLinkCount() + " link seçildi. Kontrol başlatılabilir.");
        } else {
            btnStartCheck.setEnabled(false);
            btnStartCheck.setAlpha(0.5f);
            if (!hasSelectedPanels && hasLinks) tvProgress.setText("❌ Lütfen en az bir panel seçin!");
        }
    }

    private int getFilteredLinkCount() {
        List<String> allLinks = getAllLinks();
        int count = 0;
        for (String link : allLinks) {
            if (selectedPanels.contains(extractPanelDomain(link))) count++;
        }
        return count;
    }

    private List<String> extractLinksFromManualText() {
        List<String> manualLinks = new ArrayList<>();
        String manualText = etManualLinks.getText().toString().trim();
        if (manualText.isEmpty()) return manualLinks;

        // "M3U Link:" formatını da yakalayan yeni regex
        String patternString = "(?:M3U\\s*Link\\s*:|https?://)[^\\s\"'<>]*username=[^\\s\"'&]*&password=[^\\s\"'&]*[^\\s\"'<>]*";
        Pattern pattern = Pattern.compile(patternString, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(manualText);

        while (matcher.find()) {
            String match = matcher.group().trim();
            String link = match;
            if (match.toLowerCase().contains("m3u link")) {
                link = match.replaceFirst("(?i)M3U\\s*Link\\s*:", "").trim();
            }
            link = cleanLink(link);
            if (isValidIPTVLink(link) && !manualLinks.contains(link)) {
                manualLinks.add(link);
            }
        }
        return manualLinks;
    }

    private List<String> getAllLinks() {
        List<String> allLinks = new ArrayList<>(linksList);
        allLinks.addAll(extractLinksFromManualText());
        return allLinks;
    }

    private List<String> getFilteredLinks() {
        List<String> filteredLinks = new ArrayList<>();
        for (String link : getAllLinks()) {
            if (selectedPanels.contains(extractPanelDomain(link))) filteredLinks.add(link);
        }
        return filteredLinks;
    }

    private void showFoundLinksPreview() {
        List<String> allLinks = getAllLinks();
        if (allLinks.size() > 0) {
            StringBuilder preview = new StringBuilder("Bulunan linkler:\n");
            for (int i = 0; i < Math.min(3, allLinks.size()); i++) {
                String link = allLinks.get(i);
                String shortLink = link.length() > 60 ? link.substring(0, 60) + "..." : link;
                preview.append("• ").append(shortLink).append("\n");
            }
            if (allLinks.size() > 3) preview.append("... ve ").append(allLinks.size() - 3).append(" link daha");
            Toast.makeText(this, preview.toString(), Toast.LENGTH_LONG).show();
        }
    }

    private void startIPTVCheck() {
        List<String> filteredLinks = getFilteredLinks();
        if (filteredLinks.isEmpty()) {
            Toast.makeText(this, "❌ Seçili panellere ait link bulunamadı!", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(this, ResultActivity.class);
        intent.putStringArrayListExtra("links", new ArrayList<>(filteredLinks));
        startActivity(intent);
        Toast.makeText(this, "🚀 " + filteredLinks.size() + " link kontrol ediliyor...", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        // Sadece finish - uygulamayı öldürme
        finish();
    }
}