package com.yonagi.verse.service.impl;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yonagi.verse.dao.entity.UserExternalBindingDO;
import com.yonagi.verse.dao.mapper.UserExternalBindingMapper;
import com.yonagi.verse.service.UserExternalBindingService;
import org.springframework.stereotype.Service;
@Service
public class UserExternalBindingServiceImpl extends ServiceImpl<UserExternalBindingMapper,UserExternalBindingDO> implements UserExternalBindingService { }
