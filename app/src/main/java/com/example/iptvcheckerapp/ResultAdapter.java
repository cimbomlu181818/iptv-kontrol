package com.example.iptvcheckerapp;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class ResultAdapter extends RecyclerView.Adapter<ResultAdapter.ViewHolder> {

    private List<IPTVItem> allItems;           // Tüm sonuçlar
    private List<IPTVItem> filteredItems;      // Filtrelenmiş sonuçlar
    private Context context;
    private FilterOptions filterOptions;

    public ResultAdapter(Context context, List<IPTVItem> items) {
        this.context = context;
        this.allItems = items;
        this.filteredItems = new ArrayList<>(items);
        this.filterOptions = new FilterOptions();
    }

    // Filtreyi uygula
    public void applyFilter(FilterOptions options) {
        this.filterOptions = options;
        filteredItems.clear();

        for (IPTVItem item : allItems) {
            if (filterOptions.matchesFilter(item)) {
                filteredItems.add(item);
            }
        }

        notifyDataSetChanged();
    }

    // Yeni item eklendiğinde çağrılır
    public void addItem(IPTVItem newItem) {
        allItems.add(newItem);

        // Filtreye uyuyorsa filtrelenmiş listeye de ekle
        if (filterOptions.matchesFilter(newItem)) {
            filteredItems.add(newItem);
            notifyItemInserted(filteredItems.size() - 1);
        }
    }

    // Tüm listeyi göster (filtresiz)
    public void showAll() {
        filterOptions.clearAll();
        filteredItems.clear();
        filteredItems.addAll(allItems);
        notifyDataSetChanged();
    }

    // Sadece aktif linkleri göster
    public void showActiveOnly() {
        filteredItems.clear();
        for (IPTVItem item : allItems) {
            if (item.isPartiallyActive()) {
                filteredItems.add(item);
            }
        }
        notifyDataSetChanged();
    }

    // Filtrelenmiş liste boyutu
    public int getFilteredItemCount() {
        return filteredItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.result_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        IPTVItem item = filteredItems.get(position);
        holder.bind(item, context);
    }

    @Override
    public int getItemCount() {
        return filteredItems != null ? filteredItems.size() : 0;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private CardView cardView;
        private TextView tvUsername, tvStatus, tvM3uStatus, tvApiStatus, tvChannels;
        private LinearLayout layoutDetails;
        private TextView tvPanelUrl, tvUsernameDetail, tvPassword, tvFullLink, tvUserInfo;
        private Button btnToggleDetails, btnCopyLink;
        private boolean isExpanded = false;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView = (CardView) itemView;
            tvUsername = itemView.findViewById(R.id.tvUsername);
            tvStatus = itemView.findViewById(R.id.tvStatus);
            tvM3uStatus = itemView.findViewById(R.id.tvM3uStatus);
            tvApiStatus = itemView.findViewById(R.id.tvApiStatus);
            tvChannels = itemView.findViewById(R.id.tvChannels);

            layoutDetails = itemView.findViewById(R.id.layoutDetails);
            tvPanelUrl = itemView.findViewById(R.id.tvPanelUrl);
            tvUsernameDetail = itemView.findViewById(R.id.tvUsernameDetail);
            tvPassword = itemView.findViewById(R.id.tvPassword);
            tvFullLink = itemView.findViewById(R.id.tvFullLink);
            tvUserInfo = itemView.findViewById(R.id.tvUserInfo);

            btnToggleDetails = itemView.findViewById(R.id.btnToggleDetails);
            btnCopyLink = itemView.findViewById(R.id.btnCopyLink);
        }

        public void bind(IPTVItem item, Context context) {
            // Başlık bilgileri
            String username = item.getUsername() != null ? item.getUsername() : "Bilinmiyor";
            tvUsername.setText(username);
            tvStatus.setText(item.getFormattedStatus());

            // Durum ikonları
            tvM3uStatus.setText("M3U: " + (item.isM3uActive() ? "✅" : "❌"));
            tvApiStatus.setText("API: " + (item.isApiActive() ? "✅" : "❌"));

            // Kanal sayısı
            if (item.getChannelCount() > 0) {
                tvChannels.setText("📺 " + item.getChannelCount());
                tvChannels.setVisibility(View.VISIBLE);
            } else {
                tvChannels.setVisibility(View.GONE);
            }

            // Detay bilgileri
            tvPanelUrl.setText(item.getPanelUrl() != null ? item.getPanelUrl() : "Bilinmiyor");
            tvUsernameDetail.setText(username);
            tvPassword.setText(item.getPassword() != null ? item.getPassword() : "Bilinmiyor");
            tvFullLink.setText(item.getOriginalLine());

            // Kullanıcı bilgileri
            String userInfo = item.getUserInfo();
            if (userInfo != null && !userInfo.isEmpty()) {
                tvUserInfo.setText(userInfo);
                tvUserInfo.setVisibility(View.VISIBLE);
            } else {
                tvUserInfo.setVisibility(View.GONE);
            }

            // Kart rengi
            int backgroundColor;
            if (item.isFullyActive()) {
                backgroundColor = ContextCompat.getColor(context, android.R.color.holo_green_light);
            } else if (item.isPartiallyActive()) {
                backgroundColor = ContextCompat.getColor(context, android.R.color.holo_orange_light);
            } else {
                backgroundColor = ContextCompat.getColor(context, android.R.color.holo_red_light);
            }
            cardView.setCardBackgroundColor(backgroundColor);

            // Genişlet/Daralt butonu
            btnToggleDetails.setOnClickListener(v -> {
                isExpanded = !isExpanded;
                if (isExpanded) {
                    layoutDetails.setVisibility(View.VISIBLE);
                    btnToggleDetails.setText("▲ Detayları Gizle");
                } else {
                    layoutDetails.setVisibility(View.GONE);
                    btnToggleDetails.setText("▼ Detayları Göster");
                }
            });

            // Kopyalama butonu
            btnCopyLink.setOnClickListener(v -> {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("IPTV Link", item.getOriginalLine());
                clipboard.setPrimaryClip(clip);
                Toast.makeText(context, "Link kopyalandı!", Toast.LENGTH_SHORT).show();
            });

            // Başlangıçta daralt
            layoutDetails.setVisibility(View.GONE);
            btnToggleDetails.setText("▼ Detayları Göster");
            isExpanded = false;
        }
    }
}