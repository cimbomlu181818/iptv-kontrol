package com.example.iptvcheckerapp;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.content.Intent;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ResultActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private TextView tvResultSummary, tvActiveFilters;
    private Button btnBack, btnShowActive, btnShowAll;
    private Button btnFilterMaxUsers, btnFilterDate, btnFilterStatus, btnFilterDomain, btnClearAllFilters;
    private ResultAdapter adapter;
    private List<IPTVItem> allResults = new ArrayList<>();
    private List<IPTVItem> activeResults = new ArrayList<>();
    private List<String> linksToCheck = new ArrayList<>();
    private FilterOptions currentFilter = new FilterOptions();
    private DynamicFilterManager filterManager = new DynamicFilterManager();
    private boolean showingActive = false;

    private ExecutorService executorService;
    private Handler mainHandler;
    private volatile boolean isChecking = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        android.util.Log.d("ResultActivity", "ResultActivity başlatılıyor - RESET MODE");

        // Önceki kontrol varsa durdur
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdownNow();
        }
        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        allResults.clear();
        activeResults.clear();
        linksToCheck.clear();
        filterManager.clear();
        currentFilter.clearAll();

        initViews();
        loadLinksFromIntent();
        setupRecyclerView();
        setupClickListeners();
        startRealTimeChecking();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdownNow();
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdownNow();
        }
        Intent intent = new Intent(getApplicationContext(), MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finishAffinity();
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recyclerViewResults);
        tvResultSummary = findViewById(R.id.tvResultSummary);
        tvActiveFilters = findViewById(R.id.tvActiveFilters);
        btnBack = findViewById(R.id.btnBack);
        btnShowActive = findViewById(R.id.btnShowActive);
        btnShowAll = findViewById(R.id.btnShowAll);
        btnFilterMaxUsers = findViewById(R.id.btnFilterMaxUsers);
        btnFilterDate = findViewById(R.id.btnFilterDate);
        btnFilterStatus = findViewById(R.id.btnFilterStatus);
        btnFilterDomain = findViewById(R.id.btnFilterDomain);
        btnClearAllFilters = findViewById(R.id.btnClearAllFilters);
    }

    private void loadLinksFromIntent() {
        List<String> newLinks = getIntent().getStringArrayListExtra("links");
        if (newLinks == null) {
            newLinks = new ArrayList<>();
            android.util.Log.e("ResultActivity", "Linkler intent'ten alınamadı!");
        } else {
            android.util.Log.d("ResultActivity", "Yeni linkler alındı: " + newLinks.size() + " adet");
            linksToCheck.clear();
            linksToCheck.addAll(newLinks);
        }
        updateSummary();
    }

    private void setupRecyclerView() {
        adapter = new ResultAdapter(this, allResults);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
    }

    private void setupClickListeners() {
        btnBack.setOnClickListener(v -> onBackPressed());

        btnShowActive.setOnClickListener(v -> {
            if (activeResults.isEmpty()) {
                Toast.makeText(this, "Henüz aktif link bulunamadı!", Toast.LENGTH_SHORT).show();
                return;
            }
            showingActive = true;
            currentFilter.clearAll();
            adapter.showActiveOnly();
            btnShowActive.setEnabled(false);
            btnShowAll.setEnabled(true);
            updateActiveFiltersDisplay();
            Toast.makeText(this, adapter.getFilteredItemCount() + " aktif link gösteriliyor", Toast.LENGTH_SHORT).show();
        });

        btnShowAll.setOnClickListener(v -> {
            showingActive = false;
            currentFilter.clearAll();
            adapter.showAll();
            btnShowActive.setEnabled(true);
            btnShowAll.setEnabled(false);
            updateActiveFiltersDisplay();
            Toast.makeText(this, allResults.size() + " link gösteriliyor", Toast.LENGTH_SHORT).show();
        });

        btnFilterMaxUsers.setOnClickListener(v -> showMaxUsersFilterDialog());
        btnFilterDate.setOnClickListener(v -> showDateFilterDialog());
        btnFilterStatus.setOnClickListener(v -> showStatusFilterDialog());
        btnFilterDomain.setOnClickListener(v -> showDomainFilterDialog());

        btnClearAllFilters.setOnClickListener(v -> {
            currentFilter.clearAll();
            showingActive = false;
            adapter.showAll();
            btnShowActive.setEnabled(true);
            btnShowAll.setEnabled(false);
            updateActiveFiltersDisplay();
            Toast.makeText(this, "Filtreler temizlendi", Toast.LENGTH_SHORT).show();
        });

        btnShowAll.setEnabled(false);
    }

    private void showMaxUsersFilterDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("👥 Kullanıcı Sayısı Filtresi");
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 30, 50, 30);
        List<Integer> availableUsers = filterManager.getAvailableMaxUsers();

        if (availableUsers.isEmpty()) {
            TextView noDataText = new TextView(this);
            noDataText.setText("Henüz kullanıcı sayısı bilgisi bulunmuyor");
            noDataText.setPadding(0, 20, 0, 20);
            layout.addView(noDataText);
        } else {
            for (Integer userCount : availableUsers) {
                CheckBox checkBox = new CheckBox(this);
                checkBox.setText(userCount + " kullanıcı");
                checkBox.setTag(userCount);
                checkBox.setChecked(currentFilter.getSelectedMaxUsers().contains(userCount));
                checkBox.setPadding(0, 10, 0, 10);
                layout.addView(checkBox);
            }
        }

        builder.setView(layout);
        builder.setPositiveButton("Uygula", (dialog, which) -> {
            currentFilter.getSelectedMaxUsers().clear();
            for (int i = 0; i < layout.getChildCount(); i++) {
                View view = layout.getChildAt(i);
                if (view instanceof CheckBox) {
                    CheckBox checkBox = (CheckBox) view;
                    if (checkBox.isChecked()) {
                        Integer userCount = (Integer) checkBox.getTag();
                        currentFilter.getSelectedMaxUsers().add(userCount);
                    }
                }
            }
            applyFiltersAndUpdate();
        });
        builder.setNegativeButton("İptal", null);
        builder.setNeutralButton("Temizle", (dialog, which) -> {
            currentFilter.getSelectedMaxUsers().clear();
            applyFiltersAndUpdate();
        });
        builder.show();
    }

    private void showDateFilterDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("📅 Tarih Filtresi");
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 30, 50, 30);
        List<String> availableYears = filterManager.getAvailableYears();

        if (availableYears.isEmpty()) {
            TextView noDataText = new TextView(this);
            noDataText.setText("Henüz tarih bilgisi bulunmuyor");
            noDataText.setPadding(0, 20, 0, 20);
            layout.addView(noDataText);
        } else {
            for (String year : availableYears) {
                CheckBox checkBox = new CheckBox(this);
                checkBox.setText(year);
                checkBox.setTag(year);
                checkBox.setChecked(currentFilter.getSelectedYears().contains(year));
                checkBox.setPadding(0, 10, 0, 10);
                layout.addView(checkBox);
            }
        }

        builder.setView(layout);
        builder.setPositiveButton("Uygula", (dialog, which) -> {
            currentFilter.getSelectedYears().clear();
            for (int i = 0; i < layout.getChildCount(); i++) {
                View view = layout.getChildAt(i);
                if (view instanceof CheckBox) {
                    CheckBox checkBox = (CheckBox) view;
                    if (checkBox.isChecked()) {
                        String year = (String) checkBox.getTag();
                        currentFilter.getSelectedYears().add(year);
                    }
                }
            }
            applyFiltersAndUpdate();
        });
        builder.setNegativeButton("İptal", null);
        builder.setNeutralButton("Temizle", (dialog, which) -> {
            currentFilter.getSelectedYears().clear();
            applyFiltersAndUpdate();
        });
        builder.show();
    }

    private void showDomainFilterDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("🌐 Domain Filtresi");
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 30, 50, 30);
        List<String> availableDomains = filterManager.getAvailableDomains();

        if (availableDomains.isEmpty()) {
            TextView noDataText = new TextView(this);
            noDataText.setText("Henüz domain bilgisi bulunmuyor");
            noDataText.setPadding(0, 20, 0, 20);
            layout.addView(noDataText);
        } else {
            for (String domain : availableDomains) {
                CheckBox checkBox = new CheckBox(this);
                checkBox.setText(domain);
                checkBox.setTag(domain);
                checkBox.setChecked(currentFilter.getSelectedDomains().contains(domain));
                checkBox.setPadding(0, 10, 0, 10);
                layout.addView(checkBox);
            }
        }

        builder.setView(layout);
        builder.setPositiveButton("Uygula", (dialog, which) -> {
            currentFilter.getSelectedDomains().clear();
            for (int i = 0; i < layout.getChildCount(); i++) {
                View view = layout.getChildAt(i);
                if (view instanceof CheckBox) {
                    CheckBox checkBox = (CheckBox) view;
                    if (checkBox.isChecked()) {
                        String domain = (String) checkBox.getTag();
                        currentFilter.getSelectedDomains().add(domain);
                    }
                }
            }
            applyFiltersAndUpdate();
        });
        builder.setNegativeButton("İptal", null);
        builder.setNeutralButton("Temizle", (dialog, which) -> {
            currentFilter.getSelectedDomains().clear();
            applyFiltersAndUpdate();
        });
        builder.show();
    }

    private void showStatusFilterDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("✅ Durum Filtresi");
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 30, 50, 30);

        CheckBox cbFullyActive = new CheckBox(this);
        cbFullyActive.setText("Tam Aktif (M3U + API)");
        cbFullyActive.setChecked(currentFilter.isShowFullyActive());
        layout.addView(cbFullyActive);

        CheckBox cbPartiallyActive = new CheckBox(this);
        cbPartiallyActive.setText("Kısmi Aktif (M3U veya API)");
        cbPartiallyActive.setChecked(currentFilter.isShowPartiallyActive());
        layout.addView(cbPartiallyActive);

        CheckBox cbInactive = new CheckBox(this);
        cbInactive.setText("Pasif");
        cbInactive.setChecked(currentFilter.isShowInactive());
        layout.addView(cbInactive);

        TextView systemTitle = new TextView(this);
        systemTitle.setText("Sistem Durumu:");
        systemTitle.setTextSize(16);
        systemTitle.setPadding(0, 20, 0, 10);
        layout.addView(systemTitle);

        CheckBox cbM3uActive = new CheckBox(this);
        cbM3uActive.setText("M3U Aktif");
        cbM3uActive.setChecked(currentFilter.isShowM3uActive());
        layout.addView(cbM3uActive);

        CheckBox cbApiActive = new CheckBox(this);
        cbApiActive.setText("API Aktif");
        cbApiActive.setChecked(currentFilter.isShowApiActive());
        layout.addView(cbApiActive);

        TextView activeConnTitle = new TextView(this);
        activeConnTitle.setText("Aktif Bağlantı Sayısı:");
        activeConnTitle.setTextSize(16);
        activeConnTitle.setPadding(0, 20, 0, 10);
        layout.addView(activeConnTitle);

        List<Integer> availableActiveConnections = getAvailableActiveConnections();
        for (Integer connCount : availableActiveConnections) {
            CheckBox checkBox = new CheckBox(this);
            checkBox.setText(connCount + " aktif bağlantı");
            checkBox.setTag(connCount);
            checkBox.setChecked(currentFilter.getSelectedActiveConnections().contains(connCount));
            checkBox.setPadding(0, 10, 0, 10);
            layout.addView(checkBox);
        }

        TextView trialTitle = new TextView(this);
        trialTitle.setText("Trial Durumu:");
        trialTitle.setTextSize(16);
        trialTitle.setPadding(0, 20, 0, 10);
        layout.addView(trialTitle);

        RadioGroup rgTrial = new RadioGroup(this);
        rgTrial.setOrientation(LinearLayout.VERTICAL);
        RadioButton rbTrialAll = new RadioButton(this);
        rbTrialAll.setText("Tümü");
        rbTrialAll.setId(View.generateViewId());
        rgTrial.addView(rbTrialAll);
        RadioButton rbTrialOnly = new RadioButton(this);
        rbTrialOnly.setText("Sadece Trial");
        rbTrialOnly.setId(View.generateViewId());
        rgTrial.addView(rbTrialOnly);
        RadioButton rbNonTrialOnly = new RadioButton(this);
        rbNonTrialOnly.setText("Sadece Normal");
        rbNonTrialOnly.setId(View.generateViewId());
        rgTrial.addView(rbNonTrialOnly);

        if (currentFilter.getShowTrialOnly() == null) rbTrialAll.setChecked(true);
        else if (currentFilter.getShowTrialOnly()) rbTrialOnly.setChecked(true);
        else rbNonTrialOnly.setChecked(true);

        layout.addView(rgTrial);
        builder.setView(layout);

        builder.setPositiveButton("Uygula", (dialog, which) -> {
            currentFilter.setShowFullyActive(cbFullyActive.isChecked());
            currentFilter.setShowPartiallyActive(cbPartiallyActive.isChecked());
            currentFilter.setShowInactive(cbInactive.isChecked());
            currentFilter.setShowM3uActive(cbM3uActive.isChecked());
            currentFilter.setShowApiActive(cbApiActive.isChecked());

            currentFilter.getSelectedActiveConnections().clear();
            for (int i = 0; i < layout.getChildCount(); i++) {
                View view = layout.getChildAt(i);
                if (view instanceof CheckBox) {
                    CheckBox checkBox = (CheckBox) view;
                    Object tag = checkBox.getTag();
                    if (tag instanceof Integer && checkBox.isChecked()) {
                        currentFilter.getSelectedActiveConnections().add((Integer) tag);
                    }
                }
            }

            int selectedTrialId = rgTrial.getCheckedRadioButtonId();
            if (selectedTrialId == rbTrialAll.getId()) currentFilter.setShowTrialOnly(null);
            else if (selectedTrialId == rbTrialOnly.getId()) currentFilter.setShowTrialOnly(true);
            else if (selectedTrialId == rbNonTrialOnly.getId()) currentFilter.setShowTrialOnly(false);

            applyFiltersAndUpdate();
        });
        builder.setNegativeButton("İptal", null);
        builder.setNeutralButton("Temizle", (dialog, which) -> {
            currentFilter.setShowFullyActive(false);
            currentFilter.setShowPartiallyActive(false);
            currentFilter.setShowInactive(false);
            currentFilter.setShowM3uActive(false);
            currentFilter.setShowApiActive(false);
            currentFilter.getSelectedActiveConnections().clear();
            currentFilter.setShowTrialOnly(null);
            applyFiltersAndUpdate();
        });
        builder.show();
    }

    private List<Integer> getAvailableActiveConnections() {
        List<Integer> connections = new ArrayList<>();
        for (IPTVItem item : allResults) {
            int activeUsers = item.getActiveUsers();
            if (!connections.contains(activeUsers)) connections.add(activeUsers);
        }
        connections.sort(Integer::compareTo);
        return connections;
    }

    private void applyFiltersAndUpdate() {
        showingActive = false;
        adapter.applyFilter(currentFilter);
        btnShowActive.setEnabled(true);
        btnShowAll.setEnabled(currentFilter.hasActiveFilters());
        updateActiveFiltersDisplay();
        String message = adapter.getFilteredItemCount() + " link gösteriliyor";
        if (currentFilter.hasActiveFilters()) message += " (" + currentFilter.getActiveFilterCount() + " filtre aktif)";
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void updateActiveFiltersDisplay() {
        if (currentFilter.hasActiveFilters() || showingActive) {
            StringBuilder filterText = new StringBuilder("🔍 Aktif Filtreler: ");
            if (showingActive) filterText.append("Sadece Aktif");
            else {
                List<String> filters = new ArrayList<>();
                if (!currentFilter.getSelectedMaxUsers().isEmpty()) filters.add("Kullanıcı: " + currentFilter.getSelectedMaxUsers().size());
                if (!currentFilter.getSelectedYears().isEmpty()) filters.add("Tarih: " + currentFilter.getSelectedYears().size());
                if (currentFilter.isShowFullyActive() || currentFilter.isShowPartiallyActive() || currentFilter.isShowInactive()) filters.add("Durum");
                if (currentFilter.isShowM3uActive() || currentFilter.isShowApiActive()) filters.add("Sistem");
                if (!currentFilter.getSelectedActiveConnections().isEmpty()) filters.add("Bağlantı: " + currentFilter.getSelectedActiveConnections().size());
                if (currentFilter.getShowTrialOnly() != null) filters.add("Trial");
                if (!currentFilter.getSelectedDomains().isEmpty()) filters.add("Domain: " + currentFilter.getSelectedDomains().size());
                filterText.append(String.join(", ", filters));
            }
            tvActiveFilters.setText(filterText.toString());
            tvActiveFilters.setVisibility(View.VISIBLE);
        } else {
            tvActiveFilters.setVisibility(View.GONE);
        }
    }

    private void startRealTimeChecking() {
        if (linksToCheck.isEmpty()) {
            Toast.makeText(this, "Kontrol edilecek link bulunamadı!", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "🔄 " + linksToCheck.size() + " link kontrol ediliyor...", Toast.LENGTH_SHORT).show();

        isChecking = true;
        executorService.execute(() -> {
            IPTVChecker checker = new IPTVChecker();
            for (int i = 0; i < linksToCheck.size(); i++) {
                if (!isChecking) break;
                String link = linksToCheck.get(i);
                IPTVItem result = checker.checkIPTVLine(link);
                final IPTVItem finalResult = result;
                mainHandler.post(() -> addNewResult(finalResult));
                try { Thread.sleep(10000); } catch (InterruptedException e) { break; }
            }
            mainHandler.post(() -> {
                isChecking = false;
                Toast.makeText(ResultActivity.this,
                        "✅ Kontrol tamamlandı! " + allResults.size() + " link test edildi.",
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void addNewResult(IPTVItem newItem) {
        allResults.add(newItem);
        if (newItem.isM3uActive() || newItem.isApiActive()) activeResults.add(newItem);
        filterManager.updateFromItems(allResults);
        adapter.addItem(newItem);
        updateSummary();
    }

    private void updateSummary() {
        int totalCount = allResults.size();          // Doğrudan link sayısı
        int totalLinks = linksToCheck.size();
        int fullyActiveCount = 0, partiallyActiveCount = 0, inactiveCount = 0;
        for (IPTVItem item : allResults) {
            if (item.isFullyActive()) fullyActiveCount++;
            else if (item.isPartiallyActive()) partiallyActiveCount++;
            else inactiveCount++;
        }
        String summary = String.format(
                "Test Edilen: %d/%d | ✅ Tam Aktif: %d | ⚠️ Kısmi: %d | ❌ Pasif: %d",
                totalCount, totalLinks, fullyActiveCount, partiallyActiveCount, inactiveCount
        );
        tvResultSummary.setText(summary);
    }
}