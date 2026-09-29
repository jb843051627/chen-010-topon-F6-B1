package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fc.v2.common.support.ConvertUtil;
import com.fc.v2.mapper.auto.TToponPlateCardMapper;
import com.fc.v2.mapper.auto.TToponDistrictDocMapper;
import com.fc.v2.model.auto.TToponPlateCard;
import com.fc.v2.model.auto.TToponDistrictDoc;
import com.fc.v2.service.ITToponPlateCardService;
import com.fc.v2.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 门楼牌号段建档卡Service业务层处理
 *
 * @author fuce
 * @date 2026-09-12
 */
@Service
public class TToponPlateCardServiceImpl extends ServiceImpl<TToponPlateCardMapper, TToponPlateCard> implements ITToponPlateCardService {

    /** 档案情形：0在用 1已撤并 */
    private static final int ARCH_RETIRED = 1;

    @Autowired
    private TToponDistrictDocMapper toponDistrictDocMapper;

    @Override
    public TToponPlateCard selectTToponPlateCardById(Long id) {
        return this.baseMapper.selectOne(new QueryWrapper<TToponPlateCard>()
                .eq("id", id)
                .eq("del_flag", 0));
    }

    @Override
    public List<TToponPlateCard> selectTToponPlateCardList(Wrapper<TToponPlateCard> queryWrapper) {
        // 清单口径与详情一致：尊重调用方传入的条件（分页也由调用方 startPage），
        // 只在此兜一道未删除门，不另起炉灶硬塞状态、不另开分页。
        QueryWrapper<TToponPlateCard> wrapper = (queryWrapper instanceof QueryWrapper)
                ? (QueryWrapper<TToponPlateCard>) queryWrapper
                : new QueryWrapper<TToponPlateCard>();
        return this.baseMapper.selectList(wrapper.eq("del_flag", 0));
    }

    @Override
    public int insertTToponPlateCard(TToponPlateCard record) {
        if (record == null) {
            return 0;
        }

        record.setCreateBy(record.getBillNo());
        TToponDistrictDoc arch = loadActiveArch(record.getSiteId());
        if (arch == null) {
            return 0;
        }
        record.setSiteNo(arch.getSiteNo());
        if (StringUtils.isNotEmpty(record.getBillNo())) {
            Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<TToponPlateCard>()
                    .eq("bill_no", record.getBillNo()).eq("del_flag", 0));
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
        }
        record.setGradeLevel(gradeOf(record.getQty(), arch));

        record.setDelFlag(0);
        return this.baseMapper.insert(record);
    }

    @Override
    public int updateTToponPlateCard(TToponPlateCard record) {
        if (record == null || record.getId() == null) {
            return 0;
        }
        TToponPlateCard existing = selectTToponPlateCardById(record.getId());
        if (existing == null) {
            return 0;
        }

        if (StringUtils.isNotEmpty(record.getBillNo())) {
            Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<TToponPlateCard>()
                    .eq("bill_no", record.getBillNo()).ne("id", record.getId()).eq("del_flag", 0));
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
        }

        Integer siteId = record.getSiteId() != null ? record.getSiteId() : existing.getSiteId();
        TToponDistrictDoc arch = loadActiveArch(siteId);
        if (arch == null) {
            return 0;
        }
        record.setSiteNo(arch.getSiteNo());

        // 判档与新增同出一门（=上限升档、阈值缺失跳过该档）。
        // 数量、所属档案都没动时，沿用原定档：旧卡重开重存不得被眼下的新门槛改判，
        // 保证清单/详情/复算三处对得严。
        BigDecimal qty = record.getQty() != null ? record.getQty() : existing.getQty();
        record.setQty(qty);
        boolean qtyChanged = isValueChanged(existing.getQty(), qty);
        boolean siteChanged = existing.getSiteId() == null
                ? record.getSiteId() != null
                : !existing.getSiteId().equals(siteId);
        if (qtyChanged || siteChanged) {
            record.setGradeLevel(gradeOf(qty, arch));
        } else {
            record.setGradeLevel(existing.getGradeLevel());
        }

        record.setUpdateTime(new Date());
        return this.baseMapper.update(record, new UpdateWrapper<TToponPlateCard>()
                .eq("id", record.getId())
                .eq("del_flag", 0));
    }

    @Override
    public int deleteTToponPlateCardByIds(String ids) {
        Long[] idArr = ConvertUtil.toLongArray(ids);
        return this.baseMapper.deleteBatchIds(Arrays.asList(idArr));
    }

    @Override
    public int deleteTToponPlateCardById(Long id) {
        return this.baseMapper.deleteById(id);
    }

    /** 取在用且未删除的基础档案；已撤并/不存在一律不可作判档依据 */
    private TToponDistrictDoc loadActiveArch(Integer siteId) {
        if (siteId == null) {
            return null;
        }
        TToponDistrictDoc arch = this.toponDistrictDocMapper.selectOne(new QueryWrapper<TToponDistrictDoc>()
                .eq("id", siteId).eq("del_flag", 0));
        if (arch == null || (arch.getStatus() != null && arch.getStatus() == ARCH_RETIRED)) {
            return null;
        }
        return arch;
    }

    /**
     * 号段容量定档：恰顶到上限照边界约定升一档（严格小于本档上限才留本档），
     * 阈值缺失跳过该档。与勘定基准档线 levelOf 同一把尺。
     */
    private Integer gradeOf(BigDecimal qty, TToponDistrictDoc arch) {
        if (qty == null) {
            return 0;
        }
        if (arch.getTh1Max() != null && qty.compareTo(arch.getTh1Max()) < 0) {
            return 1;
        }
        if (arch.getTh2Max() != null && qty.compareTo(arch.getTh2Max()) < 0) {
            return 2;
        }
        if (arch.getTh3Max() != null && qty.compareTo(arch.getTh3Max()) < 0) {
            return 3;
        }
        return 4;
    }

    private boolean isValueChanged(BigDecimal oldVal, BigDecimal newVal) {
        if (oldVal == null) {
            return newVal != null;
        }
        return newVal == null || oldVal.compareTo(newVal) != 0;
    }
}
