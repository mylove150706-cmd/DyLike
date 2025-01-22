# DyLike

支持Webdav协议的类抖音播放器  

## 说明  

发得仓促，代码比较乱，而且问题较多，就没有上传代码，主要是基于别的项目开发的，来不及剥离  
基于ijk本地视频播放器，定位类抖音上下滑动切换播放的短视频应用  
不仅仅是短视频播放器，手动切换长视频模式，支持加载本地弹幕，也是弹幕视频播放器  
支持本地视频及WebDav视频，WebDav尚未经大量测试，兼容性不高  
早期开发版本，部分功能尚未完善，长视频模式当前封装不支持字幕轨和音频轨切换  
受限于ijk内核，可能部分视频格式不兼容，可能出现音画不同步的问题等  
以上问题如有时间和大量需求，可以通过迭代开发更换播放器内核解决  

## 功能  

当前功能：  
[x] 短视频模式，上线滑动切换视频播放  
[x] 长视频模式，装载弹幕播放  
[x] 自定义WebDav资源库，不仅限于本地视频播放  
计划功能：  
[] 自定义媒体库，选定资源库目录，添加封面，设置长短视频模式，播放时无视全局设置自动进入对应模式  
[] 更多的资源库支持，如ftp，smb等  

## 截图  

<p align = "left">
<img src="screenshots/Screenshot_home.jpg" alt="首页，记录了历史，但是刷新机制没做，双击手动刷新" width="360" />
<img src="screenshots/Screenshot_source.jpg" alt="资源库，点击+号添加，长按支持编辑修改" width="360" />
<img src="screenshots/Screenshot_dav_add.jpg" alt="Webdav站点添加" width="360" />
<img src="screenshots/Screenshot_tool.jpg" alt="设置页" width="360" />
<img src="screenshots/Screenshot_file.jpg" alt="文件浏览" width="360" />
<img src="screenshots/Screenshot_dy.jpg" alt="短视频" width="360" />
<img src="screenshots/Screenshot_dm.jpg" alt="弹幕" width="540" />
<img src="screenshots/Screenshot_ep.jpg" alt="选集" width="540" />
</p>

## 后记  

有问题反馈，理解万岁
