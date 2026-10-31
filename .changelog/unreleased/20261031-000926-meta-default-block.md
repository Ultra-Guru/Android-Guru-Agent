<!-- pr: meta-default-block -->

- ToolMetadata.meta 移除 block 参数的空默认值 —— 全部调用方本就显式传 block，默认值从未被使用；改为必传可在编译期杜绝「空元数据」误用
