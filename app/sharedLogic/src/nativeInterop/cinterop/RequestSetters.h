#import <Foundation/Foundation.h>

static inline void hashiruSetHTTPMethod(NSMutableURLRequest *request, NSString *method) {
    [request setHTTPMethod:method];
}

static inline void hashiruSetHTTPBody(NSMutableURLRequest *request, NSData *body) {
    [request setHTTPBody:body];
}

static inline void hashiruSetHTTPHeaderField(NSMutableURLRequest *request, NSString *value, NSString *field) {
    [request setValue:value forHTTPHeaderField:field];
}

static inline NSURLSessionDataTask *hashiruDataTaskWithRequest(
    NSURLSession *session,
    NSURLRequest *request,
    void (^completionHandler)(NSData * _Nullable, NSURLResponse * _Nullable, NSError * _Nullable)
) {
    return [session dataTaskWithRequest:request completionHandler:completionHandler];
}
