package com.example.iptvcheckerapp;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseExpandableListAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ThreeLevelAdapter extends BaseExpandableListAdapter implements Filterable {

    private ChannelListActivity context;
    private List<String> mainGroupList;
    private Map<String, List<String>> categoryMap;
    private Map<String, List<String>> channelMap;

    private List<String> filteredMainGroupList;
    private Map<String, List<String>> filteredCategoryMap;
    private List<SearchResult> searchResults;
    private String currentFilter = "";

    public ThreeLevelAdapter(ChannelListActivity context,
                             List<String> mainGroupList,
                             Map<String, List<String>> categoryMap,
                             Map<String, List<String>> channelMap) {
        this.context = context;
        this.mainGroupList = mainGroupList;
        this.categoryMap = categoryMap;
        this.channelMap = channelMap;
        this.filteredMainGroupList = new ArrayList<>(mainGroupList);
        this.filteredCategoryMap = new HashMap<>(categoryMap);
        this.searchResults = new ArrayList<>();
    }

    @Override
    public int getGroupCount() {
        if (!currentFilter.isEmpty()) {
            return searchResults.isEmpty() ? 0 : 1;
        }
        return filteredMainGroupList.size();
    }

    @Override
    public int getChildrenCount(int groupPosition) {
        if (!currentFilter.isEmpty()) {
            return searchResults.size();
        }
        String mainGroup = filteredMainGroupList.get(groupPosition);
        List<String> categories = filteredCategoryMap.get(mainGroup);
        return categories != null ? categories.size() : 0;
    }

    @Override
    public Object getGroup(int groupPosition) {
        if (!currentFilter.isEmpty()) {
            return "🔍 Arama Sonuçları";
        }
        return filteredMainGroupList.get(groupPosition);
    }

    @Override
    public Object getChild(int groupPosition, int childPosition) {
        if (!currentFilter.isEmpty()) {
            if (childPosition >= 0 && childPosition < searchResults.size()) {
                return searchResults.get(childPosition);
            }
            return new SearchResult("Geçersiz Sonuç", "", "", false);
        }
        if (groupPosition >= 0 && groupPosition < filteredMainGroupList.size()) {
            String mainGroup = filteredMainGroupList.get(groupPosition);
            List<String> categories = filteredCategoryMap.get(mainGroup);
            if (categories != null && childPosition >= 0 && childPosition < categories.size()) {
                return categories.get(childPosition);
            }
        }
        return "Kategori Yok";
    }

    @Override
    public long getGroupId(int groupPosition) { return groupPosition; }

    @Override
    public long getChildId(int groupPosition, int childPosition) { return childPosition; }

    @Override
    public boolean hasStableIds() { return true; }

    @Override
    public View getGroupView(int groupPosition, boolean isExpanded, View convertView, ViewGroup parent) {
        String groupTitle = (String) getGroup(groupPosition);
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.channel_group_item, parent, false);
        }
        TextView tvCategoryName = convertView.findViewById(R.id.tvCategoryName);
        TextView tvChannelCount = convertView.findViewById(R.id.tvChannelCount);
        if (tvCategoryName != null) tvCategoryName.setText(groupTitle);
        if (!currentFilter.isEmpty()) {
            if (tvChannelCount != null) tvChannelCount.setText("(" + searchResults.size() + " sonuç)");
        } else {
            String mainGroup = filteredMainGroupList.get(groupPosition);
            int totalChannels = 0;
            List<String> categories = filteredCategoryMap.get(mainGroup);
            if (categories != null) {
                for (String category : categories) {
                    String categoryKey = mainGroup + "|" + category;
                    if (channelMap.containsKey(categoryKey)) {
                        totalChannels += channelMap.get(categoryKey).size();
                    }
                }
            }
            if (tvChannelCount != null) tvChannelCount.setText("(" + totalChannels + " kanal)");
        }
        return convertView;
    }

    @Override
    public View getChildView(int groupPosition, int childPosition, boolean isLastChild, View convertView, ViewGroup parent) {
        if (!currentFilter.isEmpty()) {
            return getSearchResultView(childPosition, convertView, parent);
        } else {
            return getCategoryView(groupPosition, childPosition, convertView, parent);
        }
    }

    private View getSearchResultView(int childPosition, View convertView, ViewGroup parent) {
        if (childPosition < 0 || childPosition >= searchResults.size()) {
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(R.layout.search_channel_item, parent, false);
            }
            TextView tvChannelName = convertView.findViewById(R.id.tvSearchChannelName);
            if (tvChannelName != null) tvChannelName.setText("Yükleniyor...");
            return convertView;
        }
        SearchResult result = searchResults.get(childPosition);
        if (result.isCategory()) {
            if (convertView == null || !"category".equals(convertView.getTag())) {
                convertView = LayoutInflater.from(context).inflate(R.layout.search_category_item, parent, false);
                convertView.setTag("category");
            }
            TextView tvCategoryName = convertView.findViewById(R.id.tvSearchCategoryName);
            TextView tvCategoryCount = convertView.findViewById(R.id.tvSearchCategoryCount);
            if (tvCategoryName != null) tvCategoryName.setText("🔍 " + result.getName());
            if (tvCategoryCount != null) {
                String categoryKey = result.getMainGroup() + "|" + result.getName();
                int channelCount = channelMap.containsKey(categoryKey) ? channelMap.get(categoryKey).size() : 0;
                tvCategoryCount.setText("(" + channelCount + " kanal)");
            }
        } else {
            if (convertView == null || !"channel".equals(convertView.getTag())) {
                convertView = LayoutInflater.from(context).inflate(R.layout.search_channel_item, parent, false);
                convertView.setTag("channel");
            }
            TextView tvChannelName = convertView.findViewById(R.id.tvSearchChannelName);
            if (tvChannelName != null) tvChannelName.setText("📺 " + result.getName());
        }
        return convertView;
    }

    private View getCategoryView(int groupPosition, int childPosition, View convertView, ViewGroup parent) {
        convertView = LayoutInflater.from(context).inflate(R.layout.channel_child_item, parent, false);
        TextView tvChannelName = convertView.findViewById(R.id.tvChannelName);
        if (tvChannelName != null) {
            String category = (String) getChild(groupPosition, childPosition);
            tvChannelName.setText(category != null ? category : "Kategori");
        }
        return convertView;
    }

    @Override
    public boolean isChildSelectable(int groupPosition, int childPosition) { return true; }

    @Override
    public Filter getFilter() {
        return new Filter() {
            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                FilterResults results = new FilterResults();
                List<SearchResult> filteredResults = new ArrayList<>();
                currentFilter = constraint != null ? constraint.toString().toLowerCase().trim() : "";
                if (currentFilter.isEmpty()) {
                    filteredMainGroupList = new ArrayList<>(mainGroupList);
                    filteredCategoryMap = new HashMap<>(categoryMap);
                } else {
                    searchForResults(currentFilter, filteredResults);
                }
                results.values = filteredResults;
                results.count = filteredResults.size();
                return results;
            }

            @Override
            protected void publishResults(CharSequence constraint, FilterResults results) {
                if (results.values instanceof List) {
                    searchResults = (List<SearchResult>) results.values;
                } else {
                    searchResults = new ArrayList<>();
                }
                notifyDataSetChanged();
            }
        };
    }

    private void searchForResults(String query, List<SearchResult> results) {
        results.clear();
        for (String mainGroup : mainGroupList) {
            List<String> categories = categoryMap.get(mainGroup);
            if (categories != null) {
                for (String category : categories) {
                    if (category.toLowerCase().contains(query)) {
                        results.add(new SearchResult(category, mainGroup, category, true));
                    }
                    String categoryKey = mainGroup + "|" + category;
                    if (channelMap.containsKey(categoryKey)) {
                        for (String channel : channelMap.get(categoryKey)) {
                            if (channel.toLowerCase().contains(query)) {
                                results.add(new SearchResult(channel, mainGroup, category, false));
                            }
                        }
                    }
                }
            }
        }
    }

    public boolean isSearchActive() { return !currentFilter.isEmpty(); }
    public int getSearchResultCount() { return searchResults.size(); }
}