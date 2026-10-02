package com.yonagi.verse.service.impl;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yonagi.verse.dao.entity.ExternalIdentityDO;
import com.yonagi.verse.dao.mapper.ExternalIdentityMapper;
import com.yonagi.verse.service.ExternalIdentityService;
import org.springframework.stereotype.Service;
@Service
public class ExternalIdentityServiceImpl extends ServiceImpl<ExternalIdentityMapper,ExternalIdentityDO> implements ExternalIdentityService { }
