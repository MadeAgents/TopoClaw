package com.cloudcontrol.demo

import retrofit2.Response
import retrofit2.http.*

/**
 * 技能服务API接口
 * 用于从技能服务获取技能列表
 */
interface SkillServiceApi {
    /**
     * 获取适合移动端的技能列表（新端点）
     * @param name 按名称搜索（可选）
     * @param desc 按描述搜索（可选）
     * @param type 按类型搜索（可选）
     * @return 技能列表响应
     */
    @GET("get_skills_for_mobile")
    suspend fun getSkillsForMobile(
        @Query("name") name: String? = null,
        @Query("desc") desc: String? = null,
        @Query("type") type: String? = null
    ): Response<SkillServiceResponse>
    
    /**
     * 获取技能列表（旧端点，用于兼容）
     * @param name 按名称搜索（可选）
     * @param desc 按描述搜索（可选）
     * @param type 按类型搜索（可选）
     * @return 原始技能列表响应
     */
    @GET("get_skill_list")
    suspend fun getSkillList(
        @Query("name") name: String? = null,
        @Query("desc") desc: String? = null,
        @Query("type") type: String? = null
    ): Response<OldSkillServiceResponse>
    
    /**
     * 上传技能到技能服务
     * @param request 上传请求数据
     * @return 上传响应
     */
    @POST("skill_upload")
    @Headers("Content-Type: application/json")
    suspend fun uploadSkill(
        @Body request: SkillUploadRequest
    ): Response<SkillUploadResponse>
    
    /**
     * 设置技能的热门状态
     * @param skillId 技能ID（可选）
     * @param skillTitle 技能标题（可选，当skillId为空时使用）
     * @param isHot 是否为热门
     * @return 响应结果
     */
    @POST("set_skill_hot")
    suspend fun setSkillHot(
        @Query("skill_id") skillId: String? = null,
        @Query("skill_title") skillTitle: String? = null,
        @Query("is_hot") isHot: Boolean
    ): Response<Map<String, Any>>
}

