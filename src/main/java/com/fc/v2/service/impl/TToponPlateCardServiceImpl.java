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

    /** 档案情形：已撤并 */
    private static final int ARCH_MERGED = 1;
    private static final int DEL_FLAG_NORMAL = 0;
    private static final int DEL_FLAG_DELETED = 1;

    @Autowired
    private TToponDistrictDocMapper toponDistrictDocMapper;

    @Override
    public TToponPlateCard selectTToponPlateCardById(Long id) {
        return this.baseMapper.selectOne(new QueryWrapper<TToponPlateCard>()
                .eq("id", id)
                .eq("del_flag", DEL_FLAG_NORMAL));
    }

    @Override
    public List<TToponPlateCard> selectTToponPlateCardList(Wrapper<TToponPlateCard> queryWrapper) {
        // 口径与详情回查一致：只补删除标记过滤，其余条件与排序由调用方在 wrapper 里给；
        // 分页一律由 Controller 的 startPage() 统一起，不在此写死页码条数，
        // 也不把进展状态钉死成待核对，免得清单页与详情藏的不是一茬。
        QueryWrapper<TToponPlateCard> wrapper;
        if (queryWrapper instanceof QueryWrapper) {
            wrapper = (QueryWrapper<TToponPlateCard>) queryWrapper;
        } else {
            wrapper = new QueryWrapper<TToponPlateCard>();
        }
        wrapper.eq("del_flag", DEL_FLAG_NORMAL);
        return this.baseMapper.selectList(wrapper);
    }

    @Override
    public int insertTToponPlateCard(TToponPlateCard record) {
        if (record == null) {
            return 0;
        }

        record.setCreateBy(record.getBillNo());
        TToponDistrictDoc refArch = loadActiveArch(record.getSiteId());
        if (refArch == null) {
            return 0;
        }
        record.setSiteNo(refArch.getSiteNo());
        if (StringUtils.isNotEmpty(record.getBillNo())) {
            Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<TToponPlateCard>()
                    .eq("bill_no", record.getBillNo()).eq("del_flag", DEL_FLAG_NORMAL));
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
        }
        record.setGradeLevel(gradeOf(refArch, record.getQty()));

        record.setDelFlag(DEL_FLAG_NORMAL);
        return this.baseMapper.insert(record);
    }

    @Override
    public int updateTToponPlateCard(TToponPlateCard record) {
        if (record == null || record.getId() == null) {
            return 0;
        }

        TToponPlateCard existing = this.baseMapper.selectOne(new QueryWrapper<TToponPlateCard>()
                .eq("id", record.getId())
                .eq("del_flag", DEL_FLAG_NORMAL));
        if (existing == null) {
            return 0;
        }

        if (StringUtils.isNotEmpty(record.getBillNo())) {
            Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<TToponPlateCard>()
                    .eq("bill_no", record.getBillNo()).ne("id", record.getId())
                    .eq("del_flag", DEL_FLAG_NORMAL));
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
        }

        // 卡法与新立一致：所属档案必须存在且未撤并；改了号码个数就照同一把尺重判容量档，
        // 不能让老档位原封不动留着，造成清单与详情、复算与原算两般模样。
        Integer targetSiteId = record.getSiteId() != null ? record.getSiteId() : existing.getSiteId();
        TToponDistrictDoc refArch = loadActiveArch(targetSiteId);
        if (refArch == null) {
            return 0;
        }
        record.setSiteNo(refArch.getSiteNo());
        BigDecimal qty = record.getQty() != null ? record.getQty() : existing.getQty();
        record.setGradeLevel(gradeOf(refArch, qty));

        record.setUpdateTime(new Date());
        return this.baseMapper.update(record, new UpdateWrapper<TToponPlateCard>()
                .eq("id", record.getId())
                .eq("del_flag", DEL_FLAG_NORMAL));
    }

    @Override
    public int deleteTToponPlateCardByIds(String ids) {
        Long[] idArr = ConvertUtil.toLongArray(ids);
        if (idArr == null || idArr.length == 0) {
            return 0;
        }
        // 与全表 del_flag 口径一致：打删除标记，不做物理删除
        return this.baseMapper.update(null, new UpdateWrapper<TToponPlateCard>()
                .in("id", Arrays.asList(idArr))
                .eq("del_flag", DEL_FLAG_NORMAL)
                .set("del_flag", DEL_FLAG_DELETED));
    }

    @Override
    public int deleteTToponPlateCardById(Long id) {
        if (id == null) {
            return 0;
        }
        return this.baseMapper.update(null, new UpdateWrapper<TToponPlateCard>()
                .eq("id", id)
                .eq("del_flag", DEL_FLAG_NORMAL)
                .set("del_flag", DEL_FLAG_DELETED));
    }

    /** 取档案：不存在 / 已删除 / 已撤并一律视为不可用，新立改动同一道卡口 */
    private TToponDistrictDoc loadActiveArch(Integer siteId) {
        if (siteId == null) {
            return null;
        }
        TToponDistrictDoc arch = this.toponDistrictDocMapper.selectOne(new QueryWrapper<TToponDistrictDoc>()
                .eq("id", siteId).eq("del_flag", DEL_FLAG_NORMAL));
        if (arch == null) {
            return null;
        }
        if (arch.getStatus() != null && arch.getStatus() == ARCH_MERGED) {
            return null;
        }
        return arch;
    }

    /**
     * 容量定档，与界线勘定基准同一把尺：恰顶到上限的升一档
     * （qty 等于 thN_max 归第 N+1 档）；qty 或阈值缺失时返回 0（未定档）。
     */
    private Integer gradeOf(TToponDistrictDoc arch, BigDecimal qty) {
        if (arch == null || qty == null) {
            return 0;
        }
        if (arch.getTh1Max() == null && arch.getTh2Max() == null && arch.getTh3Max() == null) {
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
}
