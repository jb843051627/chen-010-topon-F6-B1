package com.fc.v2.service.impl;

import org.springframework.stereotype.Service;

import com.fc.v2.mapper.auto.TToponNameBillMapper;
import com.fc.v2.model.auto.TToponNameBill;
import com.fc.v2.service.ITToponNameBillService;

/**
 * 地名命名更名逐级核准单 Service业务层处理（approval-chain 形状：多阶段签批）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TToponNameBillServiceImpl implements ITToponNameBillService {

    /** 所在层 0..3，共四层；第 3 层签足即核讫 */
    private static final int MAX_NODE = 3;
    private static final int MODE_OR = 0;
    private static final int MODE_AND = 1;
    private static final int STATUS_RUNNING = 0;
    private static final int STATUS_PASS = 1;
    private static final int STATUS_VETO = 2;
    private static final int DEL_FLAG_NORMAL = 0;

    @javax.annotation.Resource
    private TToponNameBillMapper toponNameBillMapper;

    @Override
    public TToponNameBill selectTToponNameBillById(Long id) {
        if (id == null) {
            return null;
        }
        return this.toponNameBillMapper.selectById(id);
    }

    @Override
    public TToponNameBill approve(Long id, String approver, String comment) {
        TToponNameBill r = loadRunning(id);
        if (r == null || approver == null || approver.trim().isEmpty()) {
            return null;
        }
        int signed = r.getSignCount() == null ? 0 : r.getSignCount();
        int required = requiredSigns(r);
        if (signed >= required) {
            // 本层已签足，不能再往同一摞上重复落印
            return null;
        }
        signed++;
        r.setSignCount(signed);
        r.setRemark(comment);
        if (signed >= required) {
            // 本层点齐：清零进下一层；已是末层则核讫，不再往上凭空多出一层
            r.setSignCount(0);
            int node = r.getNodeNo() == null ? 0 : r.getNodeNo();
            if (node >= MAX_NODE) {
                r.setStatus(STATUS_PASS);
            } else {
                r.setNodeNo(node + 1);
                r.setStatus(STATUS_RUNNING);
            }
        }
        this.toponNameBillMapper.updateById(r);
        return r;
    }

    @Override
    public TToponNameBill reject(Long id, String approver, String comment) {
        TToponNameBill r = loadRunning(id);
        if (r == null || approver == null || approver.trim().isEmpty()) {
            return null;
        }
        // 否决落终态"已打回"，不能只空写一道
        r.setStatus(STATUS_VETO);
        r.setRemark(comment);
        this.toponNameBillMapper.updateById(r);
        return r;
    }

    @Override
    public TToponNameBill rollback(Long id, String comment) {
        TToponNameBill r = loadRunning(id);
        if (r == null) {
            return null;
        }
        int node = r.getNodeNo() == null ? 0 : r.getNodeNo();
        int signed = r.getSignCount() == null ? 0 : r.getSignCount();
        if (node <= 0 && signed <= 0) {
            // 已在头层且本层尚未落印，无处可退
            return null;
        }
        if (signed > 0) {
            // 先清掉本层已落的印，退回重签
            r.setSignCount(0);
        } else {
            r.setNodeNo(node - 1);
            r.setSignCount(0);
        }
        r.setStatus(STATUS_RUNNING);
        r.setRemark(comment);
        this.toponNameBillMapper.updateById(r);
        return r;
    }

    /** 本层应落印数：两名点齐(AND)取 needCount，任一(OR)即 1；口径签批/复核共用 */
    private int requiredSigns(TToponNameBill r) {
        int mode = r.getSignMode() == null ? MODE_OR : r.getSignMode();
        if (mode == MODE_AND) {
            int need = r.getNeedCount() == null ? 0 : r.getNeedCount();
            return Math.max(1, need);
        }
        return 1;
    }

    private TToponNameBill loadRunning(Long id) {
        if (id == null) {
            return null;
        }
        TToponNameBill r = this.toponNameBillMapper.selectById(id);
        if (r == null || (r.getDelFlag() != null && r.getDelFlag() != DEL_FLAG_NORMAL)) {
            return null;
        }
        // 分叉两头（签批/否决/退回）同一道前置：已核讫、已打回的死单不再动
        if (r.getStatus() != null && r.getStatus() != STATUS_RUNNING) {
            return null;
        }
        return r;
    }
}
