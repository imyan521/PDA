#include <algorithm>
#include <cmath>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <vector>

struct P { double x, y; };
struct Pair { P source, target; double error; };
struct Fit { double angle=0, x=0, y=0, rmse=1e9, overlap=0, score=1e9; };
struct Scan { long long time; std::vector<P> points; };

std::vector<Pair> match(const std::vector<P>& source, const std::vector<P>& target, const Fit& f) {
    std::vector<Pair> pairs;
    const double c=std::cos(f.angle), s=std::sin(f.angle);
    for (const auto& p:source) {
        const double x=c*p.x-s*p.y+f.x, y=s*p.x+c*p.y+f.y;
        double best=.65*.65; P nearest{};
        for (const auto& q:target) {
            const double d=(q.x-x)*(q.x-x)+(q.y-y)*(q.y-y);
            if(d<best) { best=d; nearest=q; }
        }
        if(best<.65*.65) pairs.push_back({p,nearest,best});
    }
    std::sort(pairs.begin(),pairs.end(),[](const Pair&a,const Pair&b){return a.error<b.error;});
    return pairs;
}

Fit icp(const std::vector<P>& source,const std::vector<P>& target,double seed) {
    Fit f; f.angle=seed;
    for(int iteration=0;iteration<30;++iteration) {
        auto pairs=match(source,target,f);
        const size_t count=pairs.size()*4/5;
        if(count<15) return f;
        P a{},b{};
        for(size_t i=0;i<count;++i) { a.x+=pairs[i].source.x; a.y+=pairs[i].source.y; b.x+=pairs[i].target.x; b.y+=pairs[i].target.y; }
        a.x/=count;a.y/=count;b.x/=count;b.y/=count;
        double dot=0,cross=0;
        for(size_t i=0;i<count;++i) {
            const double x=pairs[i].source.x-a.x,y=pairs[i].source.y-a.y;
            const double u=pairs[i].target.x-b.x,v=pairs[i].target.y-b.y;
            dot+=x*u+y*v; cross+=x*v-y*u;
        }
        const double angle=std::atan2(cross,dot), c=std::cos(angle),s=std::sin(angle);
        const double tx=b.x-c*a.x+s*a.y,ty=b.y-s*a.x-c*a.y;
        const double change=std::abs(angle-f.angle)+std::hypot(tx-f.x,ty-f.y);
        f.angle=angle;f.x=tx;f.y=ty;
        if(change<1e-6) break;
    }
    auto pairs=match(source,target,f);
    if(pairs.size()<20) return f;
    const size_t count=pairs.size()*4/5;
    double sum=0;for(size_t i=0;i<count;++i)sum+=pairs[i].error;
    f.rmse=std::sqrt(sum/count);
    f.overlap=double(pairs.size())/source.size();
    f.score=f.rmse+.2*(1-f.overlap);
    return f;
}

int main(int argc,char**argv) {
    if(argc!=2) return 2;
    std::ifstream input(argv[1]);
    std::vector<Scan> scans;
    long long time;size_t count;
    while(input>>time>>count) {
        Scan scan;scan.time=time;
        for(size_t i=0;i<count;++i) { P p;input>>p.x>>p.y;scan.points.push_back(p); }
        scans.push_back(std::move(scan));
    }
    std::cout<<"previous_ns,current_ns,angle_deg,tx,ty,rmse,overlap\n"<<std::setprecision(12);
    for(size_t i=1;i<scans.size();++i) {
        Fit best;
        for(double degrees: {-30.,-20.,-10.,-5.,0.,5.,10.,20.,30.}) {
            Fit f=icp(scans[i].points,scans[i-1].points,degrees*std::acos(-1.)/180.);
            if(f.score<best.score) best=f;
        }
        std::cout<<scans[i-1].time<<','<<scans[i].time<<','<<best.angle*180/std::acos(-1.)<<','<<best.x<<','<<best.y<<','<<best.rmse<<','<<best.overlap<<'\n';
    }
}
