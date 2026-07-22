package io.multiverseportals.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

public final class InventoryCodec {

    private InventoryCodec() {}

    public static byte[] encode(PlayerInventory inv) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (BukkitObjectOutputStream oos = new BukkitObjectOutputStream(bos)) {
                ItemStack[] contents = inv.getContents();
                oos.writeInt(contents.length);
                for (ItemStack item : contents) {
                    oos.writeObject(item);
                }
                ItemStack[] armor = inv.getArmorContents();
                oos.writeInt(armor.length);
                for (ItemStack item : armor) {
                    oos.writeObject(item);
                }
                oos.writeObject(inv.getItemInOffHand());
            }
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("encode inventory", e);
        }
    }

    public static void decodeInto(byte[] data, PlayerInventory inv) {
        if (data == null || data.length == 0) {
            inv.clear();
            return;
        }
        try (BukkitObjectInputStream ois = new BukkitObjectInputStream(new ByteArrayInputStream(data))) {
            int contentLen = ois.readInt();
            ItemStack[] contents = new ItemStack[contentLen];
            for (int i = 0; i < contentLen; i++) {
                contents[i] = (ItemStack) ois.readObject();
            }
            inv.setContents(contents);
            int armorLen = ois.readInt();
            ItemStack[] armor = new ItemStack[armorLen];
            for (int i = 0; i < armorLen; i++) {
                armor[i] = (ItemStack) ois.readObject();
            }
            inv.setArmorContents(armor);
            inv.setItemInOffHand((ItemStack) ois.readObject());
        } catch (Exception e) {
            throw new IllegalStateException("decode inventory", e);
        }
    }
}
