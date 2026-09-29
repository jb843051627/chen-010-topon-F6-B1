package com.fc.v2.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TToponMarkerCardMapper;
import com.fc.v2.model.auto.TToponMarkerCard;
import com.fc.v2.service.ITToponMarkerCardService;

/**
 * 界桩标志补办事务卡 Service业务层处理（state-machine 形状：单据流转）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TToponMarkerCardServiceImpl implements ITToponMarkerCardService {

    /** 格口 0..5；走到第 5 格成卷 */
    private static final int MAX_STAGE = 5;
    private static final int STATUS_ACTIVE = 0;
    private static final int STATUS_DONE = 1;
    private static final int STATUS_SEALED = 2;
    private static final int DEL_FLAG_NORMAL = 0;
    private static final int DEL_FLAG_DELETED = 1;

    @javax.annotation.Resource
    private TToponMarkerCardMapper toponMarkerCardMapper;

    @Override
    public TToponMarkerCard selectTToponMarkerCardById(Long id) {
        if (id == null) {
            return null;
        }
        return this.toponMarkerCardMapper.selectById(id);
    }

    @Override
    public List<TToponMarkerCard> selectTToponMarkerCardList(QueryWrapper<TToponMarkerCard> queryWrapper) {
        QueryWrapper<TToponMarkerCard> wrapper = queryWrapper == null
                ? new QueryWrapper<TToponMarkerCard>()
                : queryWrapper;
        wrapper.eq("del_flag", DEL_FLAG_NORMAL);
        return this.toponMarkerCardMapper.selectList(wrapper);
    }

    @Override
    public TToponMarkerCard advance(Long id, String remark) {
        TToponMarkerCard r = loadLiving(id);
        if (r == null || !inProgress(r)) {
            // 无此单 / 已删 / 已成卷封存，一律拒推
            return null;
        }
        int st = r.getStage() == null ? 0 : r.getStage();
        if (st >= MAX_STAGE) {
            return null;
        }
        st++;
        r.setStage(st);
        r.setStatus(st >= MAX_STAGE ? STATUS_DONE : STATUS_ACTIVE);
        r.setLastAction(remark);
        this.toponMarkerCardMapper.updateById(r);
        return r;
    }

    @Override
    public TToponMarkerCard rollback(Long id, String remark) {
        TToponMarkerCard r = loadLiving(id);
        if (r == null || !inProgress(r)) {
            // 与推进同一道接法：办结封存的不回；无单不回
            return null;
        }
        int st = r.getStage() == null ? 0 : r.getStage();
        if (st <= 0) {
            return null;
        }
        r.setStage(st - 1);
        r.setStatus(STATUS_ACTIVE);
        r.setLastAction(remark);
        this.toponMarkerCardMapper.updateById(r);
        return r;
    }

    @Override
    public boolean updateContent(Long id, String remark) {
        TToponMarkerCard r = loadLiving(id);
        if (r == null || !inProgress(r)) {
            // 已成卷、已封存不得再改
            return false;
        }
        r.setContent(remark);
        return this.toponMarkerCardMapper.updateById(r) > 0;
    }

    @Override
    public boolean remove(Long id) {
        TToponMarkerCard r = loadLiving(id);
        if (r == null || !inProgress(r)) {
            // 已成卷、已封存不得删除
            return false;
        }
        r.setDelFlag(DEL_FLAG_DELETED);
        return this.toponMarkerCardMapper.updateById(r) > 0;
    }

    private TToponMarkerCard loadLiving(Long id) {
        if (id == null) {
            return null;
        }
        TToponMarkerCard r = this.toponMarkerCardMapper.selectById(id);
        if (r == null || (r.getDelFlag() != null && r.getDelFlag() != DEL_FLAG_NORMAL)) {
            return null;
        }
        return r;
    }

    private boolean inProgress(TToponMarkerCard r) {
        return r.getStatus() == null || r.getStatus() == STATUS_ACTIVE;
    }
}
