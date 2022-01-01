package top.ctong.gulimall.product.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import top.ctong.gulimall.common.utils.PageUtils;
import top.ctong.gulimall.common.utils.Query;
import top.ctong.gulimall.product.dao.CategoryDao;
import top.ctong.gulimall.product.entity.CategoryEntity;
import top.ctong.gulimall.product.service.CategoryBrandRelationService;
import top.ctong.gulimall.product.service.CategoryService;
import top.ctong.gulimall.product.vo.Catalog2Vo;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


/**
 * █████▒█      ██  ▄████▄   ██ ▄█▀     ██████╗ ██╗   ██╗ ██████╗
 * ▓██   ▒ ██  ▓██▒▒██▀ ▀█   ██▄█▒      ██╔══██╗██║   ██║██╔════╝
 * ▒████ ░▓██  ▒██░▒▓█    ▄ ▓███▄░      ██████╔╝██║   ██║██║  ███╗
 * ░▓█▒  ░▓▓█  ░██░▒▓▓▄ ▄██▒▓██ █▄      ██╔══██╗██║   ██║██║   ██║
 * ░▒█░   ▒▒█████▓ ▒ ▓███▀ ░▒██▒ █▄     ██████╔╝╚██████╔╝╚██████╔╝
 * ▒ ░   ░▒▓▒ ▒ ▒ ░ ░▒ ▒  ░▒ ▒▒ ▓▒     ╚═════╝  ╚═════╝  ╚═════╝
 * ░     ░░▒░ ░ ░   ░  ▒   ░ ░▒ ▒░
 * ░ ░    ░░░ ░ ░ ░        ░ ░░ ░
 * ░     ░ ░      ░  ░
 * Copyright 2021 Clover You.
 * <p>
 * 商品三级分类
 * </p>
 *
 * @author Clover You
 * @email 2621869236@qq.com
 * @create 2021-11-15 09:51:26
 */
@Service("categoryService")
public class CategoryServiceImpl extends ServiceImpl<CategoryDao, CategoryEntity> implements CategoryService {

    @Autowired
    private CategoryBrandRelationService categoryBrandRelationService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 查询叶
     *
     * @param params 自定义查询条件
     * @return PageUtils
     * @author Clover You
     * @date 2021/11/21 20:36
     */
    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<CategoryEntity> page = this.page(
                new Query<CategoryEntity>().getPage(params),
                new QueryWrapper<CategoryEntity>()
        );

        return new PageUtils(page);
    }

    /**
     * 查询所有分类并构造树形结构
     *
     * @return List<CategoryEntity>
     * @author Clover You
     * @date 2021/11/21 20:36
     */
    @Override
    public List<CategoryEntity> listWithTree() {
        // TODO 查出所有分类
        // 没有查询条件selectList可为null
        List<CategoryEntity> entities = baseMapper.selectList(null);
        // TODO 组装成树形结构
        // 找到所有一级分类
        List<CategoryEntity> level1Menus = entities.stream().filter((category) ->
                category.getParentCid() == 0
        ).map(menu -> {
            // 查找子分类
            menu.setChildren(getChildren(menu, entities));
            return menu;
        }).sorted((m1, m2) -> {
            // 排序，小的在左边，大的在右边
            return (m1.getSort() == null ? 0 : m1.getSort()) - (m2.getSort() == null ? 0 : m2.getSort());
        }).collect(Collectors.toList());

        return level1Menus;
    }

    /**
     * 通过菜单id删除菜单
     *
     * @param asList id列表
     * @author Clover You
     * @date 2021/11/22 14:48
     */
    @Override
    public void removeMenuByIds(List<Long> asList) {
        // TODO 检查当前菜单是否有子菜单
        baseMapper.deleteBatchIds(asList);
    }

    /**
     * 找到指定菜单的子菜单
     *
     * @param root 当前指定菜单
     * @param all  全部菜单
     * @return List<CategoryEntity>
     * @author Clover You
     * @date 2021/11/21 20:54
     */
    private List<CategoryEntity> getChildren(CategoryEntity root, List<CategoryEntity> all) {
        List<CategoryEntity> children = all.stream().filter(entity -> {
            return entity.getParentCid().equals(root.getCatId());
        }).map(menu -> {
            // 为当前菜单查找子菜单
            menu.setChildren(getChildren(menu, all));
            return menu;
        }).sorted((m1, m2) -> {
            return (m1.getSort() == null ? 0 : m1.getSort()) - (m2.getSort() == null ? 0 : m2.getSort());
        }).collect(Collectors.toList());
        return children;
    }

    /**
     * 通过分组id查找分组路径[父/子/孙]
     *
     * @param catelogId 分组id
     * @return Long
     * @author Clover You
     * @date 2021/11/27 08:52
     */
    @Override
    public Long[] findCategoryPath(Long catelogId) {
        List<Long> path = findParentPath(catelogId, new ArrayList<>(3));
        Collections.reverse(path);
        return path.toArray(new Long[0]);
    }

    /**
     * 通过子孙节点找所有父节点
     *
     * @param catelogId 要查找的子孙节点id
     * @param path      节点集合
     * @return List<Long>
     * @author Clover You
     * @date 2021/11/27 09:03
     */
    private List<Long> findParentPath(Long catelogId, List<Long> path) {
        path.add(catelogId);
        CategoryEntity byId = this.getById(catelogId);
        if (byId.getParentCid() != 0) {
            findParentPath(byId.getParentCid(), path);
        }
        return path;
    }

    /**
     * 集联更新分类
     *
     * @param category 分类信息
     * @author Clover You
     * @date 2021/11/27 11:00
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void updateCascade(CategoryEntity category) {
        this.updateById(category);
        if (StringUtils.hasText(category.getName())) {
            categoryBrandRelationService.updateCategory(category.getCatId(), category.getName());
        }
    }

    /**
     * 查询一级分类
     *
     * @return List<CategoryEntity>
     * @author Clover You
     * @date 2021/12/26 10:41
     */
    @Override
    public List<CategoryEntity> getLeve1Category() {
        return baseMapper.selectList(new QueryWrapper<CategoryEntity>().eq("cat_level", 1));
    }

    /**
     * getCatalogJsonLock 锁对象
     */
    private final Object getCatalogJsonLock = new Object();

    /**
     * 查出所有分类，以{"1": {Catalog2Vo}} 的形式返回，使用缓存
     *
     * @return Map<String, List < Catalog2Vo>>
     * @author Clover You
     * @date 2021/12/30 15:36
     */
    @Override
    public Map<String, List<Catalog2Vo>> getCatalogJson() {
        // 从缓存中获取数据
        String catalogJson = redisTemplate.opsForValue().get("catalogJson");
        if (!StringUtils.hasText(catalogJson)) {
            synchronized (getCatalogJsonLock) {
                // 从缓存中获取数据
                String confirmCache = redisTemplate.opsForValue().get("catalogJson");
                if (!StringUtils.hasText(confirmCache)) {
                    log.warn("从数据库中获取数据");
                    // 从数据库中获取数据
                    Map<String, List<Catalog2Vo>> catalogJsonFormDb = getCatalogJsonFormDB();
                    // 解决缓存穿透
                    if (catalogJsonFormDb == null || catalogJsonFormDb.isEmpty()) {
                        redisTemplate.opsForValue().set("catalogJson", "{}", 1, TimeUnit.DAYS);
                        return new HashMap<>(0);
                    } else {
                        String jsonString = JSON.toJSONString(catalogJsonFormDb);
                        redisTemplate.opsForValue().set("catalogJson", jsonString);
                        return catalogJsonFormDb;
                    }
                }
            }
        }
        return JSON.parseObject(catalogJson, new TypeReference<Map<String, List<Catalog2Vo>>>() {
        });
    }

    /**
     * 从数据库中查出所有分类，以{"1": {Catalog2Vo}} 的形式返回
     *
     * @return Map<String, Object>
     * @author Clover You
     * @date 2021/12/26 14:50
     */
    public Map<String, List<Catalog2Vo>> getCatalogJsonFormDB() {
        // 所有分类数据
        List<CategoryEntity> categoryEntitiesAll = baseMapper.selectList(null);
        // 查询所有一级分类
        List<CategoryEntity> leve1Category = getCategoryByParentCid(categoryEntitiesAll, 0L);
        return leve1Category.stream().collect(Collectors.toMap(k -> k.getCatId().toString(), v -> {

            List<CategoryEntity> categoryEntities = getCategoryByParentCid(categoryEntitiesAll, v.getCatId());

            List<Catalog2Vo> catalog2Vos = new ArrayList<>();
            if (categoryEntities != null) {
                catalog2Vos = categoryEntities.stream().map(item -> {
                    Catalog2Vo catalog2Vo = new Catalog2Vo(
                            v.getCatId().toString(),
                            null,
                            item.getCatId().toString(),
                            item.getName()
                    );

                    List<CategoryEntity> categoryEntityList = getCategoryByParentCid(categoryEntitiesAll, item.getCatId());
                    List<Catalog2Vo.Catalog3Vo> level3List = null;
                    if (categoryEntityList != null) {
                        level3List = categoryEntityList.stream().map(level3 -> {
                            return new Catalog2Vo.Catalog3Vo(
                                    item.getCatId().toString(),
                                    level3.getCatId().toString(),
                                    level3.getName()
                            );
                        }).collect(Collectors.toList());
                        catalog2Vo.setCatalog3List(level3List);
                    }
                    return catalog2Vo;
                }).collect(Collectors.toList());
            }
            return catalog2Vos;
        }));
    }

    /**
     * 通过父id在指定列表中查找指定项
     *
     * @param metaList 数据源
     * @return List<CategoryEntity>
     * @author Clover You
     * @date 2021/12/30 10:39
     */
    private List<CategoryEntity> getCategoryByParentCid(List<CategoryEntity> metaList, Long cId) {
        return metaList.stream().filter(item -> item.getParentCid().equals(cId)).collect(Collectors.toList());
    }
}