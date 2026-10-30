 function csrfHeaders() {
     var element = document.querySelector('meta[name="csrf-token"]');
     return element ? {'X-CSRF-Token': element.getAttribute('content')} : {};
 }
 // 统一请求头：让服务端能识别这是页面内 AJAX（会话过期时回 401 JSON 而不是 302 到登录页）
 function requestHeaders() {
     var headers = csrfHeaders();
     headers['X-Requested-With'] = 'XMLHttpRequest';
     return headers;
 }
 // 把失败原因说清楚，而不是一律「网络异常」
 function describeAjaxError(xhr) {
     var body = xhr.responseJSON;
     if (!body && xhr.responseText) {
         try { body = JSON.parse(xhr.responseText); } catch (e) { body = null; }
     }
     if (xhr.status === 401) {
         return '登录已过期，请重新登录';
     }
     if (xhr.status === 403) {
         return (body && body.errorMsg) ? body.errorMsg : '没有权限或页面已过期，请刷新页面后重试';
     }
     if (body && body.errorMsg) {
         return body.errorMsg;
     }
     if (xhr.status === 0) {
         return '网络异常：请求未发出（检查网络或服务是否在运行）';
     }
     return '请求失败（HTTP ' + xhr.status + '）';
 }
 function handleAjaxError(xhr, closeindex) {
     layer.close(closeindex);
     var msg = describeAjaxError(xhr);
     layer.msg(msg);
     if (xhr.status === 401) {
         setTimeout(function () { window.location.href = '/admin/login'; }, 1500);
     }
 }
 function ajax (type,src,data,success) {
        var closeindex = layer.load();
        $.ajax({
            type: type,
            url: src,
            data: data,
            dataType: 'json',
            headers: requestHeaders(),
            xhrFields: {
                withCredentials: true
            },
            error: function (xhr) {
                handleAjaxError(xhr, closeindex);
            },
            crossDomain: true
        }).done(function (ret) {
            console.log(ret)
            layer.close(closeindex);
            if(ret.code == -1000) {
                window.location.href="/"
                return false;
            }
            success?success(ret):function(){};
        });
}
 function ajaxjson (src,data,success) {
     var closeindex = layer.load();
     $.ajax({
         type: "POST",
         url: src,
         data: JSON.stringify(data),
         contentType: "application/json; charset=utf-8",
         dataType: 'json',
         headers: requestHeaders(),
         xhrFields: {
             withCredentials: true
         },
         error: function (xhr) {
             handleAjaxError(xhr, closeindex);
         },
         crossDomain: true
     }).done(function (ret) {
         console.log(ret)
         layer.close(closeindex);
         if(ret.code == -1000) {
             window.location.href="/"
             return false;
         }
         success?success(ret):function(){};
     });
 }
