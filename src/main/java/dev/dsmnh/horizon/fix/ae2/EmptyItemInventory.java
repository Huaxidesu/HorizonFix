package dev.dsmnh.horizon.fix.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.StorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStackType;
import appeng.api.storage.data.IItemList;
import appeng.util.item.AEItemStackType;

/**
 * 永远为空的物品库存。
 *
 * <p>交给存储总线后，总线得到一个合法但不含任何物品的库存：网络不会读到抽屉内容，
 * 也不会因为拿不到 handler 而反复重建缓存。存储总线在没有监视器时会自行休眠，
 * 轮询开销随之为零。</p>
 */
public final class EmptyItemInventory implements IMEInventory<IAEItemStack> {

    public static final EmptyItemInventory INSTANCE = new EmptyItemInventory();

    private EmptyItemInventory() {}

    @Override
    public IAEItemStack injectItems(IAEItemStack input, Actionable type, BaseActionSource src) {
        // 原样返回表示一件也没有存入
        return input;
    }

    @Override
    public IAEItemStack extractItems(IAEItemStack request, Actionable mode, BaseActionSource src) {
        return null;
    }

    @Override
    public IItemList<IAEItemStack> getAvailableItems(IItemList<IAEItemStack> out, int iteration) {
        return out;
    }

    // IMEInventory 的两个默认实现互相调用，必须显式给出，否则取值时栈溢出
    @Override
    public IAEStackType<?> getStackType() {
        return AEItemStackType.ITEM_STACK_TYPE;
    }

    @Override
    public StorageChannel getChannel() {
        return StorageChannel.ITEMS;
    }
}
