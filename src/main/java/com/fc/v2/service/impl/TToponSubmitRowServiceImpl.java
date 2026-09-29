package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TToponSubmitRowMapper;
import com.fc.v2.model.auto.TToponSubmitRow;
import com.fc.v2.service.ITToponSubmitRowService;

/**
 * 地名数据汇交核收行 Service业务层处理（batch-process 形状：整批提交）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TToponSubmitRowServiceImpl implements ITToponSubmitRowService {

    private static final int MAX_ROWS = 500;
    private static final int STATUS_OK = 1;
    private static final int STATUS_FAIL = 2;
    private static final int DEL_FLAG_NORMAL = 0;

    @javax.annotation.Resource
    private TToponSubmitRowMapper toponSubmitRowMapper;

    @Override
    public TToponSubmitRow selectTToponSubmitRowById(Long id) {
        if (id == null) {
            return null;
        }
        return this.toponSubmitRowMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int submitBatch(String batchNo, List<TToponSubmitRow> rows) {
        if (batchNo == null || batchNo.trim().isEmpty()) {
            return 0;
        }
        if (rows == null || rows.isEmpty()) {
            // 空批次返回 0
            return 0;
        }
        if (rows.size() > MAX_ROWS) {
            // 超过单批上限：-1 且整批一笔都不入
            return -1;
        }

        // 同册重交不重入：册号已有落账记录的，直接回当下账上的成功行数，
        // 不允许同一摞东西再算一遍算出另一副结论。
        Integer existed = this.toponSubmitRowMapper.selectCount(new QueryWrapper<TToponSubmitRow>()
                .eq("batch_no", batchNo)
                .eq("del_flag", DEL_FLAG_NORMAL));
        if (existed != null && existed > 0) {
            Integer priorOk = this.toponSubmitRowMapper.selectCount(new QueryWrapper<TToponSubmitRow>()
                    .eq("batch_no", batchNo)
                    .eq("status", STATUS_OK)
                    .eq("del_flag", DEL_FLAG_NORMAL));
            return priorOk == null ? 0 : priorOk;
        }

        // 先逐行校验分流，全部判完再统一落笔，避免半批入账半批挂驳回
        List<TToponSubmitRow> valid = new ArrayList<TToponSubmitRow>();
        List<TToponSubmitRow> invalid = new ArrayList<TToponSubmitRow>();
        int idx = 0;
        for (TToponSubmitRow r : rows) {
            idx++;
            if (r == null) {
                continue;
            }
            // 原始行次保留；未带行次的按册内次序补
            if (r.getRowNo() == null) {
                r.setRowNo(idx);
            }
            r.setBatchNo(batchNo);
            r.setDelFlag(DEL_FLAG_NORMAL);
            if (r.getItemCode() == null || r.getItemCode().trim().isEmpty()
                    || r.getQty() == null
                    || r.getQty().compareTo(BigDecimal.ZERO) <= 0) {
                r.setStatus(STATUS_FAIL);
                invalid.add(r);
            } else {
                r.setStatus(STATUS_OK);
                valid.add(r);
            }
        }

        for (TToponSubmitRow r : invalid) {
            this.toponSubmitRowMapper.insert(r);
        }
        for (TToponSubmitRow r : valid) {
            this.toponSubmitRowMapper.insert(r);
        }
        return valid.size();
    }

    @Override
    public List<TToponSubmitRow> listErrors(String batchNo) {
        return this.toponSubmitRowMapper.selectList(new QueryWrapper<TToponSubmitRow>()
                .eq("batch_no", batchNo)
                .eq("status", STATUS_FAIL)
                .eq("del_flag", DEL_FLAG_NORMAL)
                .orderByAsc("row_no"));
    }
}
