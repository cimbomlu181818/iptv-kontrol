package com.example.iptvcheckerapp;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseExpandableListAdapter;
import android.widget.TextView;
import java.util.List;
import java.util.Map;

public class ChannelAdapter extends BaseExpandableListAdapter {

    private ChannelListActivity context;
    private List<String> categoryList;
    private Map<String, List<String>> channelMap;

    public ChannelAdapter(ChannelListActivity context, List<String> categoryList, Map<String, List<String>> channelMap) {
        this.context = context;
        this.categoryList = categoryList;
        this.channelMap = channelMap;
    }

    @Override
    public int getGroupCount() {
        return categoryList.size();
    }

    @Override
    public int getChildrenCount(int groupPosition) {
        String category = categoryList.get(groupPosition);
        return channelMap.get(category).size();
    }

    @Override
    public Object getGroup(int groupPosition) {
        return categoryList.get(groupPosition);
    }

    @Override
    public Object getChild(int groupPosition, int childPosition) {
        String category = categoryList.get(groupPosition);
        return channelMap.get(category).get(childPosition);
    }

    @Override
    public long getGroupId(int groupPosition) {
        return groupPosition;
    }

    @Override
    public long getChildId(int groupPosition, int childPosition) {
        return childPosition;
    }

    @Override
    public boolean hasStableIds() {
        return true;
    }

    @Override
    public View getGroupView(int groupPosition, boolean isExpanded, View convertView, ViewGroup parent) {
        String category = (String) getGroup(groupPosition);

        if (convertView == null) {
            LayoutInflater inflater = context.getLayoutInflater();
            convertView = inflater.inflate(R.layout.channel_group_item, null);
        }

        TextView tvCategoryName = convertView.findViewById(R.id.tvCategoryName);
        TextView tvChannelCount = convertView.findViewById(R.id.tvChannelCount);

        tvCategoryName.setText(category);

        // Kategorideki kanal sayısını göster
        int channelCount = getChildrenCount(groupPosition);
        tvChannelCount.setText("(" + channelCount + ")");

        return convertView;
    }

    @Override
    public View getChildView(int groupPosition, int childPosition, boolean isLastChild, View convertView, ViewGroup parent) {
        String channelName = (String) getChild(groupPosition, childPosition);

        if (convertView == null) {
            LayoutInflater inflater = context.getLayoutInflater();
            convertView = inflater.inflate(R.layout.channel_child_item, null);
        }

        TextView tvChannelName = convertView.findViewById(R.id.tvChannelName);
        tvChannelName.setText(channelName);

        return convertView;
    }

    @Override
    public boolean isChildSelectable(int groupPosition, int childPosition) {
        return true;
    }
}