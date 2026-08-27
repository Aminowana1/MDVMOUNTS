package com.mdvcraft.mdvmounts.storage;

import org.bukkit.Material;

import java.util.List;

public record InvokerStorageProfile(
        String key,
        boolean enabled,
        int slots,
        List<Integer> pages,
        String title,
        double interactionDistance,
        Material material,
        String displayName,
        List<String> requiredMountTags) {

    public InvokerStorageProfile {
        pages = pages == null ? List.of() : List.copyOf(pages);
        requiredMountTags = requiredMountTags == null ? List.of() : List.copyOf(requiredMountTags);
    }

    public boolean paginated() {
        return pages.size() >= 2;
    }

    public int pageCount() {
        return paginated() ? pages.size() : 1;
    }

    public int pageGuiSize(int pageIndex) {
        if (!paginated()) {
            return Math.max(9, Math.min(54, ((slots + 8) / 9) * 9));
        }
        return pages.get(pageIndex);
    }

    public int pageUsableSlots(int pageIndex) {
        if (!paginated()) {
            return slots;
        }
        return pageGuiSize(pageIndex) - 2;
    }

    public int pageDataOffset(int pageIndex) {
        if (!paginated()) {
            return 0;
        }
        int offset = 0;
        for (int page = 0; page < pageIndex; page++) {
            offset += pageUsableSlots(page);
        }
        return offset;
    }

    public int totalUsableSlots() {
        if (!paginated()) {
            return slots;
        }
        int total = 0;
        for (int page = 0; page < pageCount(); page++) {
            total += pageUsableSlots(page);
        }
        return total;
    }

    public int previousButtonSlot(int pageIndex) {
        return paginated() ? pageGuiSize(pageIndex) - 9 : -1;
    }

    public int nextButtonSlot(int pageIndex) {
        return paginated() ? pageGuiSize(pageIndex) - 1 : -1;
    }
}
