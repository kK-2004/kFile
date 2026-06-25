package com.kk.share.sync;

import com.kk.share.entity.ShareLink;

/**
 * 访问时同步策略：按 {@link ShareLink#getShareType()} 分发，更新 {@code share_link_item}
 * 使其反映来源（文件夹 / 多选文件 / 项目提交）的当前状态。同步逻辑 SHALL 幂等。
 */
public interface ShareSyncStrategy {

    /**
     * 同步指定分享链接的条目。重复同步相同源状态 SHALL NOT 产生重复条目或错误。
     *
     * @param link 待同步的分享链接
     */
    void sync(ShareLink link);
}
