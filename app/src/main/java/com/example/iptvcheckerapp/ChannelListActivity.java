package com.example.iptvcheckerapp;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ExpandableListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.activity.OnBackPressedCallback;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChannelListActivity extends AppCompatActivity {

    private ExpandableListView expandableListView;
    private TextView tvChannelStats;
    private Button btnBack;
    private SearchView searchView;
    private ThreeLevelAdapter adapter;

    private List<String> mainGroupList;
    private Map<String, List<String>> categoryMap;
    private Map<String, List<String>> channelMap;
    private List<String> linksToTry;

    private boolean isSearchActive = false;
    private android.app.AlertDialog currentChannelDialog;

    private ExecutorService executorService;
    private Handler mainHandler;
    private volatile boolean isDiscovering = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_list);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        initViews();
        setupClickListeners();
        setupSearchView();

        linksToTry = getIntent().getStringArrayListExtra("links");
        if (linksToTry == null || linksToTry.isEmpty()) {
            Toast.makeText(this, "Link bulunamadı!", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        startChannelDiscovery();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (currentChannelDialog != null && currentChannelDialog.isShowing()) {
                    currentChannelDialog.dismiss();
                    currentChannelDialog = null;
                    return;
                }
                if (isSearchActive && searchView != null) {
                    resetSearchCompletely();
                    return;
                }
                if (isDiscovering) {
                    isDiscovering = false;
                    if (executorService != null) executorService.shutdownNow();
                }
                Intent intent = new Intent(getApplicationContext(), MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finishAffinity();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (currentChannelDialog != null && currentChannelDialog.isShowing()) currentChannelDialog.dismiss();
        if (executorService != null && !executorService.isShutdown()) executorService.shutdownNow();
    }

    private void initViews() {
        expandableListView = findViewById(R.id.expandableChannelList);
        tvChannelStats = findViewById(R.id.tvChannelStats);
        btnBack = findViewById(R.id.btnBackFromChannels);
        searchView = findViewById(R.id.searchView);
        mainGroupList = new ArrayList<>();
        categoryMap = new HashMap<>();
        channelMap = new HashMap<>();
    }

    private void setupSearchView() {
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) { return false; }
            @Override
            public boolean onQueryTextChange(String newText) {
                if (adapter != null) {
                    isSearchActive = !newText.isEmpty();
                    adapter.getFilter().filter(newText);
                    if (isSearchActive && adapter.getGroupCount() > 0) expandableListView.expandGroup(0);
                    else {
                        for (int i = 0; i < adapter.getGroupCount(); i++) expandableListView.collapseGroup(i);
                    }
                }
                return true;
            }
        });
        // HATA DÜZELTİLDİ: OnCloseListener parametre almaz, lambda -> () -> {}
        searchView.setOnCloseListener(() -> {
            resetSearchCompletely();
            return false;
        });
    }

    private void setupClickListeners() {
        btnBack.setOnClickListener(v -> {
            if (isDiscovering) {
                isDiscovering = false;
                if (executorService != null) executorService.shutdownNow();
            }
            Intent intent = new Intent(getApplicationContext(), MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finishAffinity();
        });

        expandableListView.setOnGroupClickListener((parent, v, groupPosition, id) -> {
            if (isSearchActive) return groupPosition != 0;
            return false;
        });

        expandableListView.setOnChildClickListener((parent, v, groupPosition, childPosition, id) -> {
            if (isSearchActive) handleSearchItemClick(groupPosition, childPosition);
            else showChannelsDialog(groupPosition, childPosition);
            return true;
        });

        expandableListView.setOnGroupExpandListener(groupPosition -> {
            for (int i = 0; i < adapter.getGroupCount(); i++) {
                if (i != groupPosition) expandableListView.collapseGroup(i);
            }
        });
    }

    private void resetSearchCompletely() {
        if (searchView != null) {
            searchView.setQuery("", false);
            searchView.clearFocus();
            isSearchActive = false;
            if (adapter != null) adapter.getFilter().filter("");
        }
    }

    private void handleSearchItemClick(int groupPosition, int childPosition) {
        if (adapter != null) {
            Object item = adapter.getChild(groupPosition, childPosition);
            if (item instanceof SearchResult) {
                SearchResult result = (SearchResult) item;
                if (result.isCategory()) {
                    showChannelsForSearchCategory(result);
                } else {
                    scrollToChannelCategory(result);
                }
            }
        }
    }

    private void showChannelsForSearchCategory(SearchResult categoryResult) {
        String categoryKey = categoryResult.getMainGroup() + "|" + categoryResult.getName();
        List<String> channels = channelMap.get(categoryKey);
        if (channels != null && !channels.isEmpty()) {
            String[] channelArray = channels.toArray(new String[0]);
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
            builder.setTitle("📺 " + categoryResult.getName() + " (" + channels.size() + " kanal)");
            builder.setItems(channelArray, null);
            builder.setPositiveButton("Kapat", null);
            currentChannelDialog = builder.create();
            currentChannelDialog.show();
        } else {
            Toast.makeText(this, categoryResult.getName() + " kategorisinde kanal bulunamadı!", Toast.LENGTH_SHORT).show();
        }
    }

    private void scrollToChannelCategory(SearchResult channelResult) {
        try {
            String targetCategory = channelResult.getParentCategory();
            String targetMainGroup = channelResult.getMainGroup();
            int mainGroupIndex = mainGroupList.indexOf(targetMainGroup);
            if (mainGroupIndex != -1) {
                List<String> categories = categoryMap.get(targetMainGroup);
                if (categories != null) {
                    int categoryIndex = categories.indexOf(targetCategory);
                    if (categoryIndex != -1) {
                        showLongToast("📺 " + channelResult.getName() + " kanalı\n" +
                                "📁 " + targetCategory + " kategorisinde", 4);
                        showChannelsForCategoryWithScroll(targetMainGroup, targetCategory, channelResult.getName());
                        return;
                    }
                }
            }
            Toast.makeText(this, "Kategori bulunamadı", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Hata: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    private void showChannelsForCategoryWithScroll(String mainGroup, String category, String targetChannel) {
        String categoryKey = mainGroup + "|" + category;
        List<String> channels = channelMap.get(categoryKey);
        if (channels != null && !channels.isEmpty()) {
            String[] channelArray = channels.toArray(new String[0]);
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
            builder.setTitle("📺 " + category + " (" + channels.size() + " kanal)");
            android.widget.ArrayAdapter<String> arrayAdapter = new android.widget.ArrayAdapter<String>(
                    this, android.R.layout.simple_list_item_1, channelArray) {
                @Override
                public View getView(int position, View convertView, android.view.ViewGroup parent) {
                    View view = super.getView(position, convertView, parent);
                    TextView textView = (TextView) view.findViewById(android.R.id.text1);
                    if (channels.get(position).equals(targetChannel)) {
                        textView.setBackgroundColor(0x3000FF00);
                        textView.setPadding(20, 15, 20, 15);
                    } else {
                        textView.setBackgroundColor(0x00000000);
                        textView.setPadding(20, 15, 20, 15);
                    }
                    return view;
                }
            };
            builder.setAdapter(arrayAdapter, null);
            builder.setPositiveButton("Kapat", null);
            currentChannelDialog = builder.create();
            currentChannelDialog.show();
            currentChannelDialog.getWindow().getDecorView().postDelayed(() -> {
                try {
                    android.widget.ListView listView = currentChannelDialog.getListView();
                    if (listView != null) {
                        int targetPosition = channels.indexOf(targetChannel);
                        if (targetPosition != -1) listView.setSelection(targetPosition);
                    }
                } catch (Exception e) { e.printStackTrace(); }
            }, 300);
        } else {
            Toast.makeText(this, category + " kategorisinde kanal bulunamadı!", Toast.LENGTH_SHORT).show();
        }
    }

    private void showChannelsDialog(int groupPosition, int childPosition) {
        String mainGroup = mainGroupList.get(groupPosition);
        List<String> categories = categoryMap.get(mainGroup);
        if (categories == null || childPosition >= categories.size()) return;
        String category = categories.get(childPosition);
        String categoryKey = mainGroup + "|" + category;
        List<String> channels = channelMap.get(categoryKey);
        if (channels != null && !channels.isEmpty()) {
            String[] channelArray = channels.toArray(new String[0]);
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
            builder.setTitle("📺 " + category + " (" + channels.size() + " kanal)");
            builder.setItems(channelArray, null);
            builder.setPositiveButton("Kapat", null);
            currentChannelDialog = builder.create();
            currentChannelDialog.show();
        } else {
            Toast.makeText(this, category + " kategorisinde kanal bulunamadı!", Toast.LENGTH_SHORT).show();
        }
    }

    private void startChannelDiscovery() {
        tvChannelStats.setText("🔍 Kanal listesi aranıyor...");
        isDiscovering = true;
        executorService.execute(() -> {
            int maxLinks = Math.min(20, linksToTry.size());
            boolean success = false;
            int totalChannels = 0, totalMainGroups = 0, totalCategories = 0;

            for (int i = 0; i < maxLinks && isDiscovering; i++) {
                final int currentIndex = i;  // effectively final değişken
                final String currentLink = linksToTry.get(i);
                mainHandler.post(() -> tvChannelStats.setText("⏳ " + (currentIndex + 1) + "/" + maxLinks + " deneniyor: " + getDomainFromUrl(currentLink)));
                try {
                    String m3uContent = getChannelsFromUrl(currentLink);
                    if (m3uContent != null && !m3uContent.isEmpty()) {
                        Map<String, Map<String, List<String>>> mainGroupData = M3UParser.parseM3UContent(m3uContent);
                        if (!mainGroupData.isEmpty()) {
                            mainGroupList.clear();
                            categoryMap.clear();
                            channelMap.clear();
                            mainGroupList.addAll(mainGroupData.keySet());
                            for (Map.Entry<String, Map<String, List<String>>> mainGroupEntry : mainGroupData.entrySet()) {
                                String mainGroup = mainGroupEntry.getKey();
                                Map<String, List<String>> categories = mainGroupEntry.getValue();
                                List<String> categoryList = new ArrayList<>(categories.keySet());
                                categoryMap.put(mainGroup, categoryList);
                                for (Map.Entry<String, List<String>> categoryEntry : categories.entrySet()) {
                                    String category = categoryEntry.getKey();
                                    String categoryKey = mainGroup + "|" + category;
                                    List<String> channels = categoryEntry.getValue();
                                    channelMap.put(categoryKey, channels);
                                    totalChannels += channels.size();
                                }
                            }
                            totalMainGroups = mainGroupList.size();
                            totalCategories = categoryMap.values().stream().mapToInt(List::size).sum();
                            success = true;
                            break;
                        }
                    }
                } catch (Exception e) { e.printStackTrace(); }
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            }

            final boolean finalSuccess = success;
            final int finalTotalMainGroups = totalMainGroups;
            final int finalTotalCategories = totalCategories;
            final int finalTotalChannels = totalChannels;
            mainHandler.post(() -> {
                isDiscovering = false;
                if (finalSuccess) {
                    String statsText = String.format("✅ %d ana grup, %d kategori, %d kanal bulundu",
                            finalTotalMainGroups, finalTotalCategories, finalTotalChannels);
                    tvChannelStats.setText(statsText);
                    adapter = new ThreeLevelAdapter(ChannelListActivity.this, mainGroupList, categoryMap, channelMap);
                    expandableListView.setAdapter(adapter);
                    if (adapter.getGroupCount() > 0) expandableListView.expandGroup(0);
                } else {
                    tvChannelStats.setText("❌ Kanal listesi bulunamadı!");
                    showLongToast("Seçili panellerde çalışan link bulunamadı", 4);
                }
            });
        });
    }

    private String getChannelsFromUrl(String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder content = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) content.append(line).append("\n");
                reader.close();
                return content.toString();
            }
        } catch (Exception e) { e.printStackTrace(); }
        return null;
    }

    private String getDomainFromUrl(String urlString) {
        try {
            String host = urlString.replaceFirst("http://", "").replaceFirst("https://", "");
            if (host.contains("/")) host = host.substring(0, host.indexOf("/"));
            if (host.contains(":")) host = host.substring(0, host.indexOf(":"));
            return host;
        } catch (Exception e) { return "unknown"; }
    }

    private void showLongToast(String message, int durationInSeconds) {
        Toast toast = Toast.makeText(ChannelListActivity.this, message, Toast.LENGTH_LONG);
        toast.show();
        new Handler().postDelayed(toast::cancel, durationInSeconds * 1000L);
    }
}